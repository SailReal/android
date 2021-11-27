package de.ninafoss.presentation.presenter

import android.content.Context
import android.os.AsyncTask
import android.os.Build
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.inject.Inject
import de.ninafoss.data.util.NetworkConnectionCheck
import de.ninafoss.domain.di.PerView
import de.ninafoss.domain.usecases.UpdateCheck
import de.ninafoss.presentation.BuildConfig
import de.ninafoss.presentation.R
import de.ninafoss.presentation.exception.ExceptionHandlers
import de.ninafoss.presentation.logging.Logfiles
import de.ninafoss.presentation.logging.ReleaseLogger
import de.ninafoss.presentation.model.ProgressModel
import de.ninafoss.presentation.ui.activity.view.SettingsView
import de.ninafoss.presentation.ui.dialog.UpdateAppAvailableDialog
import de.ninafoss.presentation.util.EmailBuilder
import de.ninafoss.presentation.util.FileUtil
import de.ninafoss.util.SharedPreferencesHandler
import timber.log.Timber

@PerView
class SettingsPresenter @Inject internal constructor(
	//private val updateCheckUseCase: DoUpdateCheckUseCase,  //
	//private val updateUseCase: DoUpdateUseCase,  //
	private val networkConnectionCheck: NetworkConnectionCheck,  //
	exceptionMappings: ExceptionHandlers,  //
	private val fileUtil: FileUtil,  //
	private val sharedPreferencesHandler: SharedPreferencesHandler
) : Presenter<SettingsView>(exceptionMappings) {

	fun onSendErrorReportClicked() {
		view?.showProgress(ProgressModel.GENERIC)
		// no usecase here because the backend is not involved
		CreateErrorReportArchiveTask().execute()
	}

	fun onDebugModeChanged(enabled: Boolean) {
		ReleaseLogger.updateDebugMode(enabled)
	}

	private fun sendErrorReport(attachment: File) {
		EmailBuilder.anEmail() //
				.to("support@nina-foss.de") //
			.withSubject(context().getString(R.string.error_report_subject)) //
			.withBody(errorReportEmailBody()) //
			.attach(attachment) //
			.send(activity())
	}

	private fun errorReportEmailBody(): String {
		val variant = when (BuildConfig.FLAVOR) {
			"apkstore" -> {
				"APK Store"
			}
			"fdroid" -> {
				"F-Droid"
			}
			else -> "Google Play"
		}
		return StringBuilder().append("## ").append(context().getString(R.string.error_report_subject)).append("\n\n") //
			.append("### ").append(context().getString(R.string.error_report_section_summary)).append('\n') //
			.append(context().getString(R.string.error_report_summary_description)).append("\n\n") //
			.append("### ").append(context().getString(R.string.error_report_section_device)).append("\n") //
			.append("Nina Foss v").append(BuildConfig.VERSION_NAME).append(" (").append(BuildConfig.VERSION_CODE).append(") ").append(variant).append("\n") //
			.append("Android ").append(Build.VERSION.RELEASE).append(" / API").append(Build.VERSION.SDK_INT).append("\n") //
			.append("Device ").append(Build.MODEL) //
			.toString()
	}

	fun onCheckUpdateClicked() {
		/*if (networkConnectionCheck.isPresent) {
			updateCheckUseCase //
				.withVersion(BuildConfig.VERSION_NAME)
				.run(object : NoOpResultHandler<Optional<UpdateCheck>>() {
					override fun onSuccess(result: Optional<UpdateCheck>) {
						if (result.isPresent) {
							updateStatusRetrieved(result.get(), context())
						} else {
							Timber.tag("SettingsPresenter").i("UpdateCheck finished, latest version")
							Toast.makeText(context(), getString(R.string.notification_update_check_finished_latest), Toast.LENGTH_SHORT).show()
						}
						sharedPreferencesHandler.updateExecuted()
						view?.refreshUpdateTimeView()
					}

					override fun onError(e: Throwable) {
						showError(e)
					}
				})
		} else {
			Toast.makeText(context(), R.string.error_update_no_internet, Toast.LENGTH_SHORT).show()
		}*/
	}

	private fun updateStatusRetrieved(updateCheck: UpdateCheck, context: Context) {
		showNextMessage(updateCheck.releaseNote(), context)
	}

	private fun showNextMessage(message: String, context: Context) {
		if (message.isNotEmpty()) {
			view?.showDialog(UpdateAppAvailableDialog.newInstance(message))
		} else {
			view?.showDialog(UpdateAppAvailableDialog.newInstance(context.getText(R.string.dialog_update_available_message).toString()))
		}
	}

	fun installUpdate() {
		/*view?.showDialog(UpdateAppDialog.newInstance())
		val uri = fileUtil.contentUriForNewTempFile("cryptomator.apk")
		val file = fileUtil.tempFile("cryptomator.apk")
		updateUseCase //
			.withFile(file) //
			.run(object : NoOpResultHandler<Void?>() {
				override fun onError(e: Throwable) {
					showError(e)
				}

				override fun onSuccess(result: Void?) {
					super.onSuccess(result)
					val intent = Intent(Intent.ACTION_VIEW)
					intent.setDataAndType(uri, "application/vnd.android.package-archive")
					intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
					intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
					context().startActivity(intent)
				}
			})*/
	}

	private inner class CreateErrorReportArchiveTask : AsyncTask<Void?, IOException?, File?>() {

		override fun doInBackground(vararg params: Void?): File? {
			return try {
				createErrorReportArchive()
			} catch (e: IOException) {
				publishProgress(e)
				null
			}
		}

		override fun onProgressUpdate(vararg values: IOException?) {
			Timber.e(values[0], "Sending error report failed")
			view?.showError(R.string.screen_settings_error_report_failed)
		}

		override fun onPostExecute(attachment: File?) {
			attachment?.let { sendErrorReport(it) }
			view?.showProgress(ProgressModel.COMPLETED)
		}
	}

	@Throws(IOException::class)
	private fun createErrorReportArchive(): File {
		val logfileArchive = prepareLogfileArchive()
		createZipArchive(logfileArchive, Logfiles.logfiles(context()))
		return logfileArchive
	}

	@Throws(IOException::class)
	private fun prepareLogfileArchive(): File {
		val logsDir = File(activity().cacheDir, "logs")
		if (!logsDir.exists() && !logsDir.mkdirs()) {
			throw IOException("Failed to create logs directory")
		}
		val logfileArchive = File(logsDir, "logs.zip")
		deleteIfExists(logfileArchive)
		return logfileArchive
	}

	@Throws(IOException::class)
	private fun createZipArchive(target: File, entries: Iterable<File>) {
		ZipOutputStream(FileOutputStream(target)).use { logs ->
			Logfiles.existingLogfiles(activity()).forEach { logfile ->
				addLogfile(logs, logfile)
			}
		}
	}

	@Throws(IOException::class)
	private fun addLogfile(logs: ZipOutputStream, logfile: File) {
		val entry = ZipEntry(logfile.name)
		entry.time = logfile.lastModified()
		logs.putNextEntry(entry)
		FileInputStream(logfile).use { inputStream ->
			val buffer = ByteArray(4096)
			var count = 0
			while (count != EOF) {
				logs.write(buffer, 0, count)
				count = inputStream.read(buffer)
			}
		}
	}

	private fun deleteIfExists(file: File) {
		if (file.exists()) {
			// noinspection ResultOfMethodCallIgnored
			file.delete()
		}
	}

	companion object {

		private const val EOF = -1
	}

	init {
		//unsubscribeOnDestroy(updateCheckUseCase, updateUseCase)
	}
}

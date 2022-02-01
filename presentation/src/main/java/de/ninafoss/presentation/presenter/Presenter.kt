package de.ninafoss.presentation.presenter

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import de.ninafoss.domain.usecases.NoOpResultHandler
import de.ninafoss.domain.usecases.ProgressAwareResultHandler
import de.ninafoss.domain.usecases.location.ProgressState
import de.ninafoss.generator.BoundCallback
import de.ninafoss.generator.InstanceState
import de.ninafoss.generator.Unsubscribable
import de.ninafoss.presentation.exception.ExceptionHandlers
import de.ninafoss.presentation.intent.IntentBuilder
import de.ninafoss.presentation.model.ProgressModel
import de.ninafoss.presentation.ui.activity.view.View
import de.ninafoss.presentation.workflow.ActivityResult
import de.ninafoss.presentation.workflow.AsyncResult
import de.ninafoss.presentation.workflow.Workflow
import timber.log.Timber
import java.io.Serializable
import java.util.*
import java.util.function.Supplier

abstract class Presenter<V : View> protected constructor(private val exceptionMappings: ExceptionHandlers) : ActivityHolder {

	var isPaused = false
		private set
	private var refreshOnBackPressEnabled = Supplier { true }

	var view: V? = null
		set(value) {
			field = value
			workflows().forEach { workflow ->
				workflow.setup(this, activity().intent)
			}
		}

	private val unsubscribables: MutableList<Unsubscribable> = ArrayList()

	protected fun unsubscribeOnDestroy(vararg unsubscribables: Unsubscribable) {
		this.unsubscribables.addAll(listOf(*unsubscribables))
	}

	fun resume() {
		logLifecycle("resume")
		isPaused = false
		dispatchLaterAsyncResults()
		resumed()
	}

	fun pause() {
		logLifecycle("pause")
		isPaused = true
	}

	fun finishWithResult(result: Serializable?) {
		finishWithResult(SINGLE_RESULT, result)
	}

	fun finishWithResultAndExtra(result: Serializable?, extraName: String?, extraResult: Serializable?) {
		val data = Intent()
		result?.let {
			data.putExtra(SINGLE_RESULT, it)
			data.putExtra(extraName, extraResult)
			activity().setResult(Activity.RESULT_OK, data)
		} ?: activity().setResult(Activity.RESULT_CANCELED)
		finish()
	}

	fun finishWithResult(resultName: String, result: Serializable?) {
		activeWorkflow()?.dispatch(result)
			?: run {
				val data = Intent()
				when (result) {
					null -> {
						activity().setResult(Activity.RESULT_CANCELED)
					}
					is Throwable -> {
						data.putExtra(resultName, result)
						activity().setResult(Activity.RESULT_CANCELED, data)
					}
					else -> {
						data.putExtra(resultName, result)
						activity().setResult(Activity.RESULT_OK, data)
					}
				}
				finish()
			}
	}

	private fun activeWorkflow(): Workflow<*>? {
		workflows().forEach { workflow ->
			if (workflow.isRunning) {
				return workflow
			}
		}
		return null
	}

	override fun activity(): Activity {
		return view!!.activity()
	}

	override fun context(): Context {
		return view!!.context()
	}

	fun getString(resId: Int): String {
		return context().getString(resId)
	}

	fun finish() {
		view?.finish()
	}

	fun startIntent(intentBuilder: IntentBuilder) {
		startIntent(intentBuilder.build(this))
	}

	fun startIntent(intent: Intent?) {
		activity().startActivity(intent)
	}

	open fun resumed() {}

	fun destroy() {
		logLifecycle("destroy")
		unsubscribeAll()
		destroyed()
	}

	protected fun unsubscribeAll() {
		unsubscribables.forEach { unsubscribable ->
			unsubscribable.unsubscribe()
		}
	}

	open fun destroyed() {}

	open fun workflows(): Iterable<Workflow<*>> {
		return emptyList()
	}

	fun onNewIntent(intent: Intent) {
		workflows().forEach { workflow ->
			workflow.complete(intent)
		}
	}

	open inner class DefaultResultHandler<T> : NoOpResultHandler<T>() {

		override fun onError(e: Throwable) {
			showError(e)
		}
	}

	open inner class DefaultProgressAwareResultHandler<T, S : ProgressState?> : ProgressAwareResultHandler.NoOp<T, S>() {

		override fun onError(e: Throwable) {
			showError(e)
		}
	}

	open inner class ProgressCompletingResultHandler<T> : DefaultResultHandler<T>() {

		override fun onFinished() {
			view?.showProgress(ProgressModel.COMPLETED)
		}
	}

	fun showError(e: Throwable) {
		view?.let { exceptionMappings.handle(it, e) }
	}

	fun showProgress(progress: ProgressModel) {
		if (!isPaused) {
			view?.showProgress(progress)
		}
	}

	@JvmField
	@InstanceState
	var nextActivityForResultRequestCode = 1

	@JvmField
	@InstanceState
	var nextRequestPermissionsRequestCode = 1

	@JvmField
	@InstanceState
	var activityResultCallbacks = HashMap<Int, BoundCallback<*, *>>()

	@JvmField
	@InstanceState
	var permissionsResultCallbacks = HashMap<Int, BoundCallback<*, *>>()

	@JvmField
	@InstanceState
	var permissionSnackbarText = HashMap<Int, Int>()
	private val toDispatchLater = Collections.synchronizedSet(HashSet<AsyncResult>())

	private fun dispatch(asyncResult: AsyncResult) {
		val callback = asyncResult.callback()
		val instance = findInstanceFor(callback)
		if (instance == null) {
			Timber.e("No instance found for callback type %s", callback.declaringType.name)
		} else {
			callback.call(instance, asyncResult)
		}
	}

    fun requestActivityResult(callback: BoundCallback<*, out ActivityResult?>, intentBuilder: IntentBuilder) {
        requestActivityResult(callback, intentBuilder.build(this))
    }

    fun requestActivityResult(callback: BoundCallback<*, out ActivityResult?>, intent: Intent?) {
        val requestCode = nextActivityForResultRequestCode++
        activityResultCallbacks[requestCode] = callback
        activity().startActivityForResult(intent, requestCode)
    }


    private fun dispatchLater(asyncResult: AsyncResult) {
		if (isPaused) {
			toDispatchLater.add(asyncResult)
		} else {
			dispatch(asyncResult)
		}
	}

	private fun dispatchLaterAsyncResults() {
		val toDispatch = toDispatchLater.iterator()
		while (toDispatch.hasNext()) {
			dispatch(toDispatch.next())
			toDispatch.remove()
		}
	}

	private fun findInstanceFor(callback: BoundCallback<*, *>): Any? {
		if (callback.declaringType.isInstance(this)) {
			return this
		}
		return workflows().firstOrNull { callback.declaringType.isInstance(it) }
	}

	fun onActivityResult(requestCode: Int, resultCode: Int, intent: Intent?) {
		val callback = activityResultCallbacks.remove(requestCode)
		if (callback == null) {
			Timber.tag("ActivityResult").w("Missing callback")
			return
		}
		if (resultCode == Activity.RESULT_OK || callback.acceptsNonOkResults()) {
			dispatch(ActivityResult(callback, intent, resultCode == Activity.RESULT_OK))
		}
	}

	private fun allGranted(grantResults: IntArray): Boolean {
		grantResults.forEach { grantResult ->
			if (grantResult != PackageManager.PERMISSION_GRANTED) {
				return false
			}
		}
		return true
	}

	private fun logLifecycle(method: String) {
		Timber.tag("PresenterLifecycle").d("$method $this")
	}

	/*fun setRefreshOnBackPressEnabled(refreshOnBackPressEnabled: BrowseFilesPresenter.RefreshSupplier) {
		this.refreshOnBackPressEnabled = refreshOnBackPressEnabled
	}*/

	fun isRefreshOnBackPressEnabled(): Boolean {
		return refreshOnBackPressEnabled.get()
	}

	companion object {

		const val SINGLE_RESULT = "singleResult"
	}
}

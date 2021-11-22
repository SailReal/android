package de.ninafoss.util.file

import android.content.Context
import android.net.Uri
import java.io.BufferedReader
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.io.PrintWriter
import java.io.StringWriter
import java.nio.charset.StandardCharsets
import java.util.UUID
import javax.inject.Inject

class FileCacheUtils @Inject constructor(context: Context) {

	private val cacheDir: File = context.cacheDir

	@Throws(IOException::class)
	fun read(inputStream: InputStream): String {
		BufferedReader(InputStreamReader(inputStream, StandardCharsets.UTF_8)).use { reader ->
			StringWriter().use { writer ->
				val buffer = CharArray(1024 * 4)
				var line: Int
				while (reader.read(buffer).also { line = it } != EOF) {
					writer.write(buffer, 0, line)
				}
				return writer.toString()
			}
		}
	}

	fun deleteTmpFile(uri: Uri) {
		uri.path?.let { File(it).delete() }
	}

	fun tmpFile(): TmpFileBuilder {
		return try {
			TmpFileBuilder(File.createTempFile(UUID.randomUUID().toString(), ".tmp", cacheDir))
		} catch (e: IOException) {
			throw IllegalStateException("Create tmp file ", e)
		}
	}

	inner class TmpFileBuilder internal constructor(private val tmpFile: File) {

		private var content: String? = null

		fun withContent(text: String?): TmpFileBuilder {
			content = text
			return this
		}

		fun empty(): TmpFileBuilder {
			content = ""
			return this
		}

		fun create(): Uri {
			writeToFile(tmpFile, content)
			return Uri.fromFile(tmpFile)
		}

		private fun writeToFile(tmpFile: File, content: String?) {
			open(tmpFile).use { writer -> writer.print(content) }
		}

		private fun open(tmpFile: File): PrintWriter {
			return try {
				PrintWriter(OutputStreamWriter(FileOutputStream(tmpFile), StandardCharsets.UTF_8))
			} catch (e: FileNotFoundException) {
				throw IllegalStateException("Opening ", e)
			}
		}
	}

	companion object {

		private const val EOF = -1
	}

}

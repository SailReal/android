package de.ninafoss.domain.usecases.location

import android.content.Context
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream

class ByteArrayDataSource private constructor(private val bytes: ByteArray) : DataSource {

	override fun size(context: Context): Long {
		return bytes.size.toLong()
	}

	@Throws(IOException::class)
	override fun open(context: Context): InputStream {
		return ByteArrayInputStream(bytes)
	}

	override fun decorate(delegate: DataSource): DataSource {
		return delegate
	}

	@Throws(IOException::class)
	override fun close() {
		// do nothing because ByteArrayInputStream need no close
	}

	companion object {

		@JvmStatic
		fun from(bytes: ByteArray): DataSource {
			return ByteArrayDataSource(bytes)
		}
	}
}

package com.jkbff.ao.chatproxy

import java.util.concurrent.{Callable, LinkedBlockingQueue, TimeUnit}

import com.jkbff.ao.tyrlib.chat.socket.Closeable
import org.apache.log4j.Logger.getLogger

class BuddyListTaskListener[T](queue: LinkedBlockingQueue[Callable[T]], onError: Closeable) extends Thread {

	private val logger = getLogger(getClass)

	var shouldStop = false

	override def run(): Unit = {
		while (!shouldStop) {
			try {
				val task = queue.poll(1, TimeUnit.SECONDS)
				if (task != null) {
					task.call()
				}
			} catch {
				case e: Exception =>
					logger.error("", e)
					onError.close()
			}
		}
	}

	def close(): Unit = {
		logger.warn("Closing buddy list task listener")
		shouldStop = true
	}
}
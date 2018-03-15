package com.jkbff.ao.chatproxy

import java.util.concurrent.{LinkedBlockingQueue, TimeUnit}

import com.jkbff.ao.tyrlib.packets.client.PrivateMessageSend
import org.apache.log4j.Logger.getLogger

class PrivateMessageListener(bot: BotManager, queue: LinkedBlockingQueue[PrivateMessageSend]) extends Thread {

  private val logger = getLogger(getClass)

  var shouldStop = false

  override def run(): Unit = {
    while (!shouldStop) {
      val p = queue.poll(1, TimeUnit.SECONDS)
      if (p != null) {
        bot.sendPacket(p)

        // wait 2s to avoid private message limiting
        try {
          Thread.sleep(2000)
        } catch {
          case e: InterruptedException =>
            logger.warn("", e)
        }
      }
    }
  }

  def close(): Unit = {
    logger.warn("Closing private message listener for " + bot.id)
    shouldStop = true
  }
}

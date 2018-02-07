package com.jkbff.ao.chatproxy

import java.util.concurrent.LinkedBlockingQueue

import com.jkbff.ao.tyrlib.packets.client.PrivateMessageSend
import org.apache.log4j.Logger.getLogger

class PrivateMessageListener(bot: BotManager, queue: LinkedBlockingQueue[PrivateMessageSend]) extends Thread {

  private val logger = getLogger(getClass)

  var shouldStop = false

  override def run(): Unit = {
    while (!shouldStop) {
      val p = queue.poll()
      if (p != null) {
        bot.sendPacket(p)
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

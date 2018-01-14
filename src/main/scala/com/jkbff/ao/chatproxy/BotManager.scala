package com.jkbff.ao.chatproxy

import com.jkbff.ao.tyrlib.chat.socket.AOClientSocket
import com.jkbff.ao.tyrlib.packets.client.BaseClientPacket

class BotManager(val id: String, aoClient: AOClientSocket, clientHandler: ClientHandler) extends Thread {
  var shouldStop = false

  override def run(): Unit = {
    try {
      aoClient.start()
      while(!shouldStop) {
        val packet = aoClient.readPacket()
        if (packet != null) {
          clientHandler.processPacket(packet, this)
        }
      }
    } catch {
      case e: Throwable =>
        e.printStackTrace()
    } finally {
      close()
    }
  }

  def sendPacket(packet: BaseClientPacket): Unit = {
    aoClient.sendPacket(packet)
  }

  def close(): Unit = {
    println("shutting down Bot Manager " + id)
    shouldStop = true
    aoClient.close()
  }
}

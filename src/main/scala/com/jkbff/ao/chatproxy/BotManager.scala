package com.jkbff.ao.chatproxy

import java.net.Socket

import com.jkbff.ao.tyrlib.chat.socket.AOClientSocket
import com.jkbff.ao.tyrlib.packets.PacketFactory
import com.jkbff.ao.tyrlib.packets.client.BaseClientPacket
import com.jkbff.ao.tyrlib.packets.server.BaseServerPacket
import org.apache.log4j.Logger.getLogger

class BotManager(val id: String, serverAddress: String, serverPort: Int, serverPacketFactory: PacketFactory[BaseServerPacket], clientHandler: ClientHandler) extends Thread {
  var shouldStop = false
  private val logger = getLogger("com.jkbff.ao.chatproxy.ClientHandler")
  val aoClientSocket = new AOClientSocket("main", new Socket(serverAddress, serverPort), serverPacketFactory, clientHandler)

  override def run(): Unit = {
    logger.debug("Starting bot manager for " + id)
    try {
      aoClientSocket.start()
      while(!shouldStop) {
        val packet = aoClientSocket.readPacket()
        if (packet != null) {
          logger.debug("Received packet from " + id + ": " + packet)
          clientHandler.processPacket(packet, this)
        }
      }
    } catch {
      case e: Throwable =>
        logger.error("", e)
    } finally {
      clientHandler.close()
    }
  }

  def sendPacket(packet: BaseClientPacket): Unit = {
    logger.debug("Sending packet to " + id + ": " + packet)
    aoClientSocket.sendPacket(packet)
  }

  def close(): Unit = {
    shouldStop = true
    logger.warn("closing bot manager " + id)
    aoClientSocket.close()
  }
}

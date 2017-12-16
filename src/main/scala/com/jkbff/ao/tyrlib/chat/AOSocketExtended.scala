package com.jkbff.ao.tyrlib.chat

import com.jkbff.ao.tyrlib.packets.{BaseServerPacket, server}
import org.apache.log4j.Logger

class AOSocketExtended(socketInfo: AOSocketInfo, chatPacketHandler: ChatPacketHandler) extends AOSocket(socketInfo: AOSocketInfo, chatPacketHandler: ChatPacketHandler) {
  val log = Logger.getLogger(this.getClass)

  override def processIncomingPacket(packet: BaseServerPacket): Unit = {
    packet match {
      case p: server.LoginSeed =>
        this.log.debug("SERVER " + p)
        this.chatPacketHandler.processPacket(p, this)
      case p: server.CharacterList =>
        this.log.debug("SERVER " + p)
        this.chatPacketHandler.processPacket(p, this)
      case _ =>
        super.processIncomingPacket(packet)
    }
  }
}

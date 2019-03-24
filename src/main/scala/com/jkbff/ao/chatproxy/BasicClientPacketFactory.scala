package com.jkbff.ao.chatproxy

import com.jkbff.ao.tyrlib.packets.ClientPacketFactory
import com.jkbff.ao.tyrlib.packets.client._

class BasicClientPacketFactory extends ClientPacketFactory {
  override def createInstance(packetId: Int, payload: Array[Byte]): BaseClientPacket = {
    packetId match {
      case LoginSelect.TYPE | LoginRequest.TYPE | BuddyAdd.TYPE | BuddyRemove.TYPE | PrivateMessageSend.TYPE =>
        super.createInstance(packetId, payload)
      case _ =>
        new GenericClientPacket(packetId, payload)
    }
  }
}

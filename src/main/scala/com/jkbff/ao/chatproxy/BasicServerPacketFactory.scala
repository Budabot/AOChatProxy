package com.jkbff.ao.chatproxy

import com.jkbff.ao.tyrlib.chat.socket.ServerPacketFactory
import com.jkbff.ao.tyrlib.packets.server._

class BasicServerPacketFactory extends ServerPacketFactory {
  override def createInstance(packetId: Int, payload: Array[Byte]): BaseServerPacket = {
    packetId match {
      case LoginOk.TYPE | FriendUpdate.TYPE | FriendRemove.TYPE | CharacterUpdate.TYPE =>
        super.createInstance(packetId, payload)
      case _ =>
        new GenericServerPacket(packetId, payload)

    }
  }
}

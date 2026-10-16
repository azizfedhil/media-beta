package com.mediahub.pear.cloud

actual fun newPartySocket(): PartySocket = okHttpPartySocket()

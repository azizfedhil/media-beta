package com.mediahub.pear.cloud

/**
 * A new socket for one connection attempt to a watch party room. Each platform makes its own: desktop and Android both use
 * OkHttp (see `okHttpPartySocket` in jvmCommonMain).
 */
expect fun newPartySocket(): PartySocket

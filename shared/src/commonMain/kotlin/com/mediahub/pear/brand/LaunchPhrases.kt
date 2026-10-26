package com.mediahub.pear.brand

import kotlin.random.Random

/** The playful lines under the launch animation. One is picked at random each launch. */
object LaunchPhrases {
    val all: List<String> = listOf(
        "Pear-ing up the good stuff…",
        "Getting a-pear-ently ready…",
        "Pear-fect timing…",
        "Ripening the pixels…",
        "Polishing the pear. to a shine…",
        "Shaking the pear. tree…",
        "Teaching the pear. to dance…",
        "Pear-sonalizing your evening…",
        "Plucking today's picks…",
        "Peeling back the layers…",
        "Popping the popcorn…",
        "Dimming the lights…",
        "Fluffing the couch cushions…",
        "Warming up the couch…",
        "Almost ripe…",
        "Grabbing the good seats…",
    )

    fun pick(random: Random = Random.Default): String = all[random.nextInt(all.size)]
}

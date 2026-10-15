package io.github.purplepalm.birdair.core

import kotlin.test.Test
import kotlin.test.assertNull

class SmokeTest {
    @Test fun empty() = assertNull(Telemetry().speedKmh)
}

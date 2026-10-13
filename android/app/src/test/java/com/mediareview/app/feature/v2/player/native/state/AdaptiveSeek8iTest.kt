package com.mediareview.app.feature.v2.player.native.state
import org.junit.Assert.*
import org.junit.Test
class AdaptiveSeek8iTest {
 @Test fun `actual body double tap remains fixed ten seconds`() {
  val tap = DoubleTapSeekState()
  assertEquals(SeekTap(40_000, 10_000), tap.tap(.8f, 1000, 30_000, 7_200_000))
  tap.reset()
  assertEquals(SeekTap(20_000, -10_000), tap.tap(.2f, 2000, 30_000, 7_200_000))
 }
 @Test fun `whole screen uses two percent bounded range`() {
  listOf(300_000L to 30_000L, 1_800_000L to 36_000L, 7_200_000L to 144_000L, 21_600_000L to 180_000L).forEach { (duration, range) -> assertEquals("duration=$duration", range, SeekGesturePreview.spanFor(duration)) }
 }
 @Test fun `real gesture mapping preserves bar and single commit`() {
  listOf(300_000L to 30_000L, 1_800_000L to 36_000L, 7_200_000L to 144_000L, 21_600_000L to 180_000L).forEach { (duration, range) ->
   assertEquals(range, PlayerSeekMapping.horizontalDelta(1000f, 1000f, duration))
   assertEquals(-range / 2, PlayerSeekMapping.horizontalFractionDelta(-.5f, duration))
   assertEquals(duration / 2, PlayerSeekMapping.position(.5f, duration))
   val drag = SeekDragEngine()
   assertTrue(drag.begin(duration / 2, duration, true))
   drag.preview(PlayerSeekMapping.horizontalTarget(duration / 2, .25f, duration))
   drag.preview(PlayerSeekMapping.horizontalTarget(duration / 2, .5f, duration))
   assertEquals(SeekCommit(duration / 2 + range / 2, true), drag.commit())
   assertNull(drag.commit())
  }
 }
 @Test fun `invalid geometry and long limits cannot overflow`() {
  for (bad in listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)) {
   assertEquals(0L, PlayerSeekMapping.horizontalDelta(bad, 100f, 300_000L))
   assertEquals(0L, PlayerSeekMapping.horizontalDelta(100f, bad, 300_000L))
  }
  assertEquals(0L, PlayerSeekMapping.horizontalDelta(1f, 0f, 300_000L))
  assertEquals(0L, PlayerSeekMapping.horizontalFractionDelta(1f, -1L))
  assertEquals(Long.MAX_VALUE, PlayerSeekMapping.horizontalTarget(Long.MAX_VALUE - 1, 1f, Long.MAX_VALUE))
  assertEquals(0L, PlayerSeekMapping.horizontalTarget(10, -Float.MAX_VALUE, Long.MAX_VALUE))
  assertEquals(SeekCommit(3000, false), SeekDragEngine().run { begin(1000, 60_000, false); preview(3000); commit() })
 }
}

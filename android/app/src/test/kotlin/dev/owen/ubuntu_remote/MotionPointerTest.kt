package dev.owen.ubuntu_remote
import org.junit.Assert.*
import org.junit.Test
class MotionPointerTest {
    private fun trajectory(gravity:FloatArray,gyro:FloatArray):Pair<Int,Int> {
        val pointer=MotionPointer(); pointer.setGravity(gravity)
        var x=0; var y=0
        repeat(101) { val result=pointer.step(gyro,1_000_000_000L+it*10_000_000L,900.0); x+=result.first; y+=result.second }
        return Pair(x,y)
    }
    @Test fun yawMatchesForUprightAndFlatGrip() {
        val upright=trajectory(floatArrayOf(0f,9.8f,0f),floatArrayOf(0f,-0.5f,0f))
        val flat=trajectory(floatArrayOf(0f,0f,9.8f),floatArrayOf(0f,0f,-0.5f))
        assertEquals(upright,flat); assertTrue(flat.first>0); assertEquals(0,flat.second)
    }
    @Test fun pitchMovesUpInEitherGrip() {
        for(gravity in listOf(floatArrayOf(0f,9.8f,0f),floatArrayOf(0f,0f,9.8f))) {
            val result=trajectory(gravity,floatArrayOf(0.5f,0f,0f)); assertEquals(0,result.first); assertTrue(result.second<0)
        }
    }
    @Test fun SmallStationaryBiasDoesNotDrift() {
        assertEquals(Pair(0,0),trajectory(floatArrayOf(0f,0f,9.8f),floatArrayOf(0.003f,0f,0.003f)))
    }
    @Test fun releasingAndRegrippingStartsWithoutJump() {
        val pointer=MotionPointer(); pointer.setGravity(floatArrayOf(0f,0f,9.8f))
        pointer.step(floatArrayOf(0f,0f,1f),1_000_000_000L,900.0); pointer.reset()
        assertEquals(Pair(0,0),pointer.step(floatArrayOf(0f,0f,1f),4_000_000_000L,900.0))
        pointer.setGravity(floatArrayOf(0f,9.8f,0f))
        assertEquals(Pair(0,0),pointer.step(floatArrayOf(0f,1f,0f),5_000_000_000L,900.0))
    }
}

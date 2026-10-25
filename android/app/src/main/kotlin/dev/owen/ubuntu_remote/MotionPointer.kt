package dev.owen.ubuntu_remote

import kotlin.math.*

/** Gravity-relative air mouse with clutch reset, tremor filtering and slow-speed precision. */
class MotionPointer {
    private val gravity=DoubleArray(3)
    private var ready=false
    private var lastTime=0L
    private var filteredYaw=0.0
    private var filteredPitch=0.0
    private var residualX=0.0
    private var residualY=0.0
    fun reset() { ready=false; lastTime=0; filteredYaw=0.0; filteredPitch=0.0; residualX=0.0; residualY=0.0 }
    fun setGravity(values:FloatArray) {
        val norm=sqrt(values.sumOf { it.toDouble()*it })
        if(norm>1.0) { for(i in 0..2) gravity[i]=values[i]/norm; ready=true }
    }
    fun step(values:FloatArray,timestamp:Long,sensitivity:Double):Pair<Int,Int> {
        if(!ready) return Pair(0,0)
        if(lastTime==0L) { lastTime=timestamp; return Pair(0,0) }
        val dt=((timestamp-lastTime)/1e9).coerceIn(0.0,0.05)
        lastTime=timestamp
        // Yaw is rotation around gravity, independent of upright/flat grip.
        val yaw=-(0..2).sumOf { values[it]*gravity[it] }
        // Project the screen's right axis onto the horizontal plane for pitch.
        val right=doubleArrayOf(1.0-gravity[0]*gravity[0],-gravity[0]*gravity[1],-gravity[0]*gravity[2])
        val norm=sqrt(right.sumOf { it*it })
        val pitch=if(norm>0.1) -(0..2).sumOf { values[it]*right[it]/norm } else 0.0
        val alpha=1-exp(-dt/0.035)
        filteredYaw+=alpha*(yaw-filteredYaw); filteredPitch+=alpha*(pitch-filteredPitch)
        fun stable(v:Double)=sign(v)*max(0.0,abs(v)-0.008)
        val vx=stable(filteredYaw); val vy=stable(filteredPitch)
        val gain=0.35+0.65*(hypot(vx,vy)/0.65).coerceIn(0.0,1.0)
        residualX+=vx*dt*sensitivity*gain; residualY+=vy*dt*sensitivity*gain
        val x=residualX.toInt(); val y=residualY.toInt()
        residualX-=x; residualY-=y
        return Pair(x,y)
    }
}

package app.seb3thehacker.gearslip.protocol

/** One finger on the car's screen: a stable [id] for as long as it stays down, and where it is. */
class TouchPoint(val id: Int, val x: Float, val y: Float)

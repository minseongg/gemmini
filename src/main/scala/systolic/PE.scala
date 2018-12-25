// See README.md for license details.

package systolic

import chisel3._

/**
  * A PE implementing a MAC operation. Configured as fully combinational when integrated into a Mesh.
  * @param width Data width of operands
  * @param pass_through If false, the PE pipelines in_a, in_b, in_propag, in_s for 1 cycle
  */
class PE(width: Int, pass_through: Boolean) extends Module {
  val io = IO(new Bundle {
    val in_a = Input(UInt(width.W))
    val out_a = Output(UInt(width.W))
    // TODO: why is in_b 2*width and not width
    val in_b = Input(UInt((2*width).W))
    val in_propag = Input(UInt((2*width).W))
    val in_s = Input(UInt(2.W))
    val out_s = Output(UInt(2.W))
    val out  = Output(UInt((2*width).W))
    val out_b  = Output(UInt((2*width).W))
  })

  val a  = if (pass_through) Wire(UInt()) else RegInit(0.U)
  val b  = if (pass_through) Wire(UInt()) else RegInit(0.U)
  val propag  = if (pass_through) Wire(UInt()) else RegInit(0.U)
  // TODO: potential for overflow in internal accumulators (add assertion) (use explicit width)
  val c1  = RegInit(0.U)
  val c2  = RegInit(0.U)
  val s  = if (pass_through) Wire(UInt()) else RegInit(0.U)

  a := io.in_a
  b := io.in_b
  propag := io.in_propag
  s := io.in_s
  io.out_s := s
  io.out_a := a

  val mode = s(1)
  val select = s(0)

  val OUTPUT_STATIONARY = 0.U(1.W)
  val WEIGHT_STATIONARY = 1.U(1.W)

  val COMPUTE = 0.U(1.W)
  val PROPAGATE = 1.U(1.W)

  when (mode === OUTPUT_STATIONARY) {
    when(select === PROPAGATE){
      io.out := c1
      io.out_b := b
      c1 := propag
      c2 := (a*b) + c2
    }.otherwise {
      io.out := c2
      io.out_b := b
      c2 := propag
      c1 := (a*b) + c1
    }
  }.otherwise {
    when(select === PROPAGATE){
      io.out := c1
      io.out_b := c1
      c1 := b
      c2 := propag
    }.otherwise {
      io.out := (a*c1) + b
      io.out_b := (a*c1) + b
      c1 := c1
      c2 := propag
    }
  }
}

object PEMain extends App {
  chisel3.Driver.execute(args, () => new PE(8,true))
}

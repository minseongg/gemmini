package systolic

import chisel3._
import chisel3.util._

// This module is meant to go inside the Load and/or Store controllers, where it can track which commands are currently
// in flight and which are completed
class DMACommandTracker[T <: Data](val nCmds: Int, val nRequests: Int, tag_t: => T) extends Module {
  def cmd_id_t = UInt((log2Ceil(nCmds) max 1).W)

  val io = IO(new Bundle {
    // TODO is there an existing decoupled interface in the standard library which matches this use-case?
    val alloc = new Bundle {
      val valid = Input(Bool())
      val ready = Output(Bool())

      val bits = new Bundle {
        val tag = Input(tag_t)
        val cmd_id = Output(cmd_id_t)
      }

      def fire(dummy: Int = 0) = valid && ready
    }

    val request_returned = Flipped(Valid(new Bundle {
      val cmd_id = cmd_id_t
    }))

    val cmd_completed = Decoupled(new Bundle {
      val cmd_id = cmd_id_t
      val tag = tag_t
    })
  })

  class Entry extends Bundle {
    val valid = Bool()
    val tag = tag_t.cloneType
    val requests_returned = UInt((log2Ceil(nRequests+1) max 1).W)
  }

  val entry_init = Wire(new Entry)
  entry_init.valid := false.B
  entry_init.tag := DontCare
  entry_init.requests_returned := DontCare

  val cmds = RegInit(VecInit(Seq.fill(nCmds)(entry_init)))
  val cmd_valids = cmds.map(_.valid)

  val next_empty_alloc = MuxCase(0.U, cmd_valids.zipWithIndex.map { case (v, i) => (!v) -> i.U })

  io.alloc.ready := !cmd_valids.reduce(_ && _)
  io.alloc.bits.cmd_id := next_empty_alloc

  val cmd_completed_id = MuxCase(0.U, cmds.zipWithIndex.map { case (cmd, i) =>
    (cmd.valid && cmd.requests_returned === nRequests.U) -> i.U
  })
  io.cmd_completed.valid := cmds.map(cmd => cmd.valid && cmd.requests_returned === nRequests.U).reduce(_ || _)
  io.cmd_completed.bits.cmd_id := cmd_completed_id
  io.cmd_completed.bits.tag := cmds(cmd_completed_id).tag

  when (io.alloc.fire()) {
    cmds(next_empty_alloc).valid := true.B
    cmds(next_empty_alloc).tag := io.alloc.bits.tag
    cmds(next_empty_alloc).requests_returned := 0.U
  }

  when (io.request_returned.fire()) {
    val cmd_id = io.request_returned.bits.cmd_id
    cmds(cmd_id).requests_returned := cmds(cmd_id).requests_returned + 1.U

    assert(cmds(cmd_id).valid)
    assert(cmds(cmd_id).requests_returned =/= nRequests.U)
  }

  when (io.cmd_completed.fire()) {
    cmds(io.cmd_completed.bits.cmd_id).valid := false.B
  }
}


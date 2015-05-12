//**************************************************************************
// RISCV Re-order Buffer
//--------------------------------------------------------------------------
//
// Christopher Celio
// 2013 Oct 18
//

// Bank the ROB, such that each "dispatch" group gets its own row of the ROB,
// and each instruction in the dispatch group goes to a different bank.
// We can compress out the PC by only saving the high-order bits.
//
// ASSUMPTION: dispatch groups are aligned to the PC.
//
// NOTES:
//    - Currently we do not compress out bubbles in the ROB.
//    - commit_width is tied directly to the dispatch_width.
//    - Exceptions are only taken when at the head of the commit bundle.
//      This helps deal with loads, stores, and refetch instructions.
//

package BOOM

import Chisel._
import Node._
import scala.math.ceil

import rocket.Str

class Exception extends BOOMCoreBundle
{
   val uop = new MicroOp()
   val cause = Bits(width=log2Up(rocket.Causes.all.max))
   val badvaddr = UInt(width=coreMaxAddrBits)
}


class RobIo(machine_width: Int
            , num_wakeup_ports: Int
            , num_fpu_ports: Int
            )  extends BOOMCoreBundle
{
   // Dispatch Stage
   // (Write Instruction to ROB from Dispatch Stage)
   val dis_mask         = Vec.fill(machine_width) { Bool(INPUT) }
   val dis_uops         = Vec.fill(machine_width) { new MicroOp().asInput() }
   val dis_pred_info    = new BranchPredictionResp().asInput

   val curr_rob_tail    = UInt(OUTPUT, ROB_ADDR_SZ)

   // Write-back Stage
   // (Update of ROB)
   // Instruction is no longer busy and can be committed
   // currently all supported exceptions are detected in Decode (except load-ordering failures)
   val wb_resps = Vec.fill(num_wakeup_ports) { Valid(new ExeUnitResp(65)).flip }

   // track side-effects for debug purposes.
   // Also need to know when loads write back, whereas we don't need loads to unbusy.
   val debug_wb_valids  = Vec.fill(num_wakeup_ports) { Bool(INPUT) }
   val debug_wb_wdata   = Vec.fill(num_wakeup_ports) { Bits(INPUT, xLen) }

   val fflags = Vec.fill(num_fpu_ports) { new ValidIO(new FFlagsResp()).flip }
   val lxcpt = new ValidIO(new Exception()).flip // LSU
   val bxcpt = new ValidIO(new Exception()).flip // BRU
   val cxcpt = new ValidIO(new Exception()).flip // CSR

   // Commit Stage
   // (Free no-longer used register).
   // Also used for rollback.
   val com_valids       = Vec.fill(machine_width) {Bool(OUTPUT)}
   val com_uops         = Vec.fill(machine_width) {new MicroOp().asOutput()}
   val com_fflags_val   = Bool(OUTPUT)
   val com_fflags       = Bits(OUTPUT, 5)

   // tell the LSU how many stores and loads are being committed
   val com_st_mask      = Vec.fill(machine_width) {Bool(OUTPUT)}
   val com_ld_mask      = Vec.fill(machine_width) {Bool(OUTPUT)}

   val lsu_clr_bsy_valid = Bool(INPUT)
   val lsu_clr_bsy_rob_idx = UInt(INPUT, ROB_ADDR_SZ)

   // Handle Exceptions/ROB Rollback
   val com_exception    = Bool(OUTPUT)
   val com_exc_cause    = UInt(OUTPUT, xLen)
   val com_handling_exc = Bool(OUTPUT)
   val com_rbk_valids   = Vec.fill(machine_width) {Bool(OUTPUT)}
   val com_badvaddr     = UInt(OUTPUT, xLen)

   // Handle Branch Misspeculations
   val br_unit          = new BranchUnitResp().asInput

   // Let the Branch Unit read out an instruction's PC
   val get_pc = new Bundle
   {
      val rob_idx  = UInt(INPUT, ROB_ADDR_SZ)
      val curr_pc  = UInt(OUTPUT, xLen)
      val next_val = Bool(OUTPUT)             // the next_pc may not be valid (stalled or still being fetched)
      val next_pc  = UInt(OUTPUT, xLen)
   }
   val get_pred = new GetPredictionInfo().flip

   // Handle Additional Misspeculations (LSU)
   // tell the LSU a misspec occurred
   val lsu_misspec      = Bool(OUTPUT)

   // When flushing pipeline, need to reset to PC+4 relative to the head of the ROB
   // but because we're doing superscalar commit, the actual flush pc may not
   // be the rob_head pc+4, but rather the last committed instruction in the
   // commit group.
   // Finally, we redirect the PC ASAP, but flush the pipeline a cycle later
   // (to get it off the critical path).
   val flush_take_pc    = Bool(OUTPUT)
   val flush_pc         = UInt(OUTPUT, vaddrBits+1)
   val flush_pipeline   = Bool(OUTPUT)

   // Stall Decode as appropriate
   val empty            = Bool(OUTPUT)
   val ready            = Bool(OUTPUT) // busy unrolling...


   // pass out debug information to high-level printf
   val debug = new BOOMCoreBundle
   {
      val state = UInt()
      val rob_head = UInt(width = ROB_ADDR_SZ)
      val xcpt_val = Bool()
      val xcpt_uop = new MicroOp()
      val xcpt_badvaddr = UInt(width = xLen)
      val entry = Vec.fill(NUM_ROB_ENTRIES) { new Bundle {
         val valid = Bool()
         val busy = Bool()
         val uop = new MicroOp()
         val exception = Bool()
         //val fflags = Bits(width=rocket.FPConstants.FLAGS_SZ)
      }}
   }.asOutput
}


// width = the dispatch and commit width of the processor
// num_wakeup_ports = self-explanatory
// num_fpu_ports = number of FPU units that will write back fflags
class Rob(width: Int
         , num_rob_entries: Int
         , num_wakeup_ports: Int
         , num_fpu_ports: Int
         ) extends Module with BOOMCoreParameters
{
   val io = new RobIo(width, num_wakeup_ports, num_fpu_ports)

   val num_rob_rows = num_rob_entries / width
   require (num_rob_rows % 2 == 0) // this is due to how rob PCs are stored in two banks
                                   // and in getting next-pc causes wrap
                                   // around, thus the banks must have equal
                                   // numbers of items.

   println("    Machine Width  : " + width); require (isPow2(width))
   println("    Rob Entries    : " + num_rob_entries)
   println("    Rob Rows       : " + num_rob_rows)
   println("    Rob Row size   : " + log2Up(num_rob_rows))
   println("    log2UP(width)  : " + log2Up(width))
   println("    log2Ceil(width): " + log2Ceil(width))

   val s_reset :: s_normal :: s_rollback :: s_wait_till_empty :: Nil = Enum(UInt(),4)
   val rob_state = Reg(init = s_reset)


   //commit entries at the head, and unwind exceptions from the tail
   val rob_head = Reg(init = UInt(0, log2Up(num_rob_rows)))
   val rob_tail = Reg(init = UInt(0, log2Up(num_rob_rows)))
   val rob_tail_idx = rob_tail << UInt(log2Ceil(width))

   val will_commit         = Vec.fill(width) {Bool()}
   val can_commit          = Vec.fill(width) {Bool()}
   val can_throw_exception = Vec.fill(width) {Bool()}
   val rob_head_vals       = Vec.fill(width) {Bool()} // are the instructions at the head valid?
   val rob_head_is_store   = Vec.fill(width) {Bool()}
   val rob_head_is_load    = Vec.fill(width) {Bool()}
   val rob_head_fflags     = Vec.fill(width) {Bits(width=rocket.FPConstants.FLAGS_SZ)}

   // valid bits at the branch target
   // the br_unit needs to verify the target PC, but it must read out the valid bits
   // for that row
   val rob_brt_vals        = Vec.fill(width) {Bool()}

   val exception_thrown = Bool()

   // exception info
   // TODO compress xcpt cause size
   val r_xcpt_val       = Reg(init=Bool(false))
   val r_xcpt_uop       = Reg(new MicroOp())
   val r_xcpt_badvaddr  = Reg(UInt(width=coreMaxAddrBits))

   //--------------------------------------------------
   // Utility

   def GetRowIdx(rob_idx: UInt): UInt =
   {
      if (width == 1) return rob_idx // TODO remove this, should be unnecessary with Ceil
      else return rob_idx >> UInt(log2Ceil(width))
   }
   def GetBankIdx(rob_idx: UInt): UInt =
   {
      if(width == 1) { return UInt(0) }
      else           { return rob_idx(log2Up(width)-1, 0).toUInt }
   }

   // **************************************************************************
   // Debug

   val debug_entry = Vec.fill(NUM_ROB_ENTRIES) { new Bundle {
         val valid = Bool()
         val busy = Bool()
         val uop = new MicroOp()
         val exception = Bool()
      }}

   // **************************************************************************
   // --------------------------------------------------------------------------
   // **************************************************************************
   //
   // PCs
   // store the high-order-bits of the PC only once per ROB row, allowing us to
   // compress out most of the expensive PC information.
   // NOTE: This works since we only write consecutive, aligned instructions
   // into the ROB row.
   //
   // The ROB PC information is written by dispatch at rob_tail.
   // The ROB PC information is read
   //       - commit for flush PC at rob_head.
   //       - execute by the Br Unit for target calculation at idx X.
   //       - execute by the Br Unit to get actual target at idx X+1.

   val rob_pc_hob = new RobPCs(width, num_rob_rows)

   when (io.dis_mask.reduce(_|_))
   {
      rob_pc_hob.write(rob_tail, io.dis_uops(0).pc) // internally, the RobPCs zero out low-order bits
   }

   // the br unit needs to read out two consecutive ROBs
   val (curr_row_pc, next_row_pc) = rob_pc_hob.read2(GetRowIdx(io.get_pc.rob_idx))

   io.get_pc.curr_pc := curr_row_pc + Cat(GetBankIdx(io.get_pc.rob_idx), Bits(0,2))

   val next_bank_idx = if (width == 1) UInt(0) else PriorityEncoder(rob_brt_vals.toBits)

   // TODO is this logic broken if the ROB can fill up completely? should I look at valid bit instead?
   val rob_pc_hob_next_val = WrapInc(GetRowIdx(io.get_pc.rob_idx), num_rob_rows) != rob_tail

   val bypass_next_bank_idx = if (width == 1) UInt(0) else PriorityEncoder(io.dis_mask.toBits)
   val bypass_next_pc = (io.dis_uops(0).pc & SInt(-(DECODE_WIDTH*coreInstBytes))) + Cat(bypass_next_bank_idx, Bits(0,2))

   io.get_pc.next_val := rob_pc_hob_next_val || io.dis_mask.reduce(_|_)
   io.get_pc.next_pc := Mux(rob_pc_hob_next_val, next_row_pc + Cat(next_bank_idx, Bits(0,2)), bypass_next_pc)

   // **************************************************************************
   // --------------------------------------------------------------------------
   // **************************************************************************
   // store expensive branch prediction information here (per br-tag)
//   val pred_table = Mem(new BranchPredictionResp, num_rob_rows)
   // TODO use Mem(), but it chokes on the undefines in VCS
   val pred_table = Vec.fill(num_rob_rows) {Reg(new BranchPredictionResp)}
   when (io.dis_mask.reduce(_|_))
   {
      pred_table(rob_tail) := io.dis_pred_info
   }

   io.get_pred.info := pred_table(GetRowIdx(io.get_pred.rob_idx))
   // **************************************************************************
   // --------------------------------------------------------------------------
   // **************************************************************************

   for (w <- 0 until width)
   {
      def MatchBank(bank_idx: UInt): Bool = (bank_idx === UInt(w))

      // one bank
      val rob_val       = Vec.fill(num_rob_rows) {Reg(init = Bool(false))}
      val rob_bsy       = Mem(Bool(), num_rob_rows)
      val rob_uop       = Vec.fill(num_rob_rows) {Reg(new MicroOp())} // one write port - dispatch
                                                           // fake write ports - clearing on commit,
                                                           // rollback, branch_kill
      val rob_exception = Mem(Bool(), num_rob_rows)        // TODO consolidate into the com_uop? what's the best for Chisel?
      val rob_fflags    = Mem(Bits(width=rocket.FPConstants.FLAGS_SZ), num_rob_rows)

      //-----------------------------------------------
      // Dispatch: Add Entry to ROB

      when (io.dis_mask(w))
      {
         rob_val(rob_tail)       := Bool(true)
         rob_bsy(rob_tail)       := io.dis_uops(w).uopc != uopSRET &&  // TODO do I need to do this for eret? or should I treat it like it's an exception
                                    !io.dis_uops(w).is_fence &&
                                    !(io.dis_uops(w).is_fencei)
         rob_uop(rob_tail)       := io.dis_uops(w)
         rob_exception(rob_tail) := io.dis_uops(w).exception
         rob_fflags(rob_tail)    := Bits(0)
         rob_uop(rob_tail).br_was_mispredicted := Bool(false)
      }
      .elsewhen (io.dis_mask.reduce(_|_))
      {
         rob_uop(rob_tail).inst := BUBBLE // just for debug purposes
      }


      //-----------------------------------------------
      // Writeback

      for (i <- 0 until num_wakeup_ports)
      {
         val wb_resp = io.wb_resps(i)
         val wb_uop = wb_resp.bits.uop
         val row_idx = GetRowIdx(wb_uop.rob_idx)
         when (wb_resp.valid && MatchBank(GetBankIdx(wb_uop.rob_idx)))
         {
            rob_bsy(row_idx) := Bool(false)
         }
         // TODO check that fflags aren't overwritten
         // TODO check that the wb is to a valid ROB entry, give it a time stamp
//         assert (!(wb_resp.valid && MatchBank(GetBankIdx(wb_uop.rob_idx)) &&
//                  wb_uop.fp_val && !(wb_uop.is_load || wb_uop.is_store) &&
//                  rob_exc_cause(row_idx) != Bits(0)),
//                  "FP instruction writing back exc bits is overriding an existing exception.")
      }

      // Stores have a separate method to clear busy bits
      when (io.lsu_clr_bsy_valid && MatchBank(GetBankIdx(io.lsu_clr_bsy_rob_idx)))
      {
         rob_bsy(GetRowIdx(io.lsu_clr_bsy_rob_idx)) := Bool(false)
      }

      when (io.br_unit.brinfo.valid && MatchBank(GetBankIdx(io.br_unit.brinfo.rob_idx)))
      {
         // these signals need to be delayed a cycle to match the brinfo signals
         rob_uop(GetRowIdx(io.br_unit.brinfo.rob_idx)).br_was_mispredicted := io.br_unit.brinfo.mispredict
      }


      //-----------------------------------------------
      // Accruing fflags
      for (i <- 0 until num_fpu_ports)
      {
         val fflag_uop = io.fflags(i).bits.uop
         when (io.fflags(i).valid && MatchBank(GetBankIdx(fflag_uop.rob_idx)))
         {
            rob_fflags(GetRowIdx(fflag_uop.rob_idx)) := io.fflags(i).bits.flags
         }
      }

      //-----------------------------------------------------
      // Exceptions
      // (the cause bits are compressed and stored elsewhere)

      when (io.lxcpt.valid && MatchBank(GetBankIdx(io.lxcpt.bits.uop.rob_idx)))
      {
         rob_exception(GetRowIdx(io.lxcpt.bits.uop.rob_idx)) := Bool(true)
      }
      when (io.bxcpt.valid && MatchBank(GetBankIdx(io.bxcpt.bits.uop.rob_idx)))
      {
         rob_exception(GetRowIdx(io.bxcpt.bits.uop.rob_idx)) := Bool(true)
      }
      can_throw_exception(w) := rob_val(rob_head) && rob_exception(rob_head)

      //-----------------------------------------------
      // Commit or Rollback

      // can this instruction commit? (the check for exceptions/rob_state happens later)
      can_commit(w) := rob_val(rob_head) && !(rob_bsy(rob_head))

      val com_idx = UInt()
      com_idx := rob_head
      when (rob_state === s_rollback)
      {
         com_idx := rob_tail
      }

      // use the same "com_uop" for both rollback AND commit
      // Perform Commit
      io.com_valids(w)     := will_commit(w)
      io.com_rbk_valids(w) := (rob_state === s_rollback) &&
                              rob_val(com_idx) &&
                              (rob_uop(com_idx).dst_rtype === RT_FIX || rob_uop(com_idx).dst_rtype === RT_FLT) &&
                              Bool(!params(EnableCommitMapTable))
      io.com_uops(w)       := rob_uop(com_idx)

      when (rob_state === s_rollback)
      {
         rob_val(com_idx)       := Bool(false)
         rob_exception(com_idx) := Bool(false)
      }

      if (params(EnableCommitMapTable))
      {
         when (Reg(next=exception_thrown))
         {
            for (i <- 0 until num_rob_rows)
            {
               rob_val(i)      := Bool(false)
               rob_bsy(i)      := Bool(false)
               rob_uop(i).inst := BUBBLE
            }
         }
      }

      // -----------------------------------------------
      // Kill speculated entries on branch mispredict
      for (i <- 0 until num_rob_rows)
      {
         val br_mask = rob_uop(i).br_mask
         val entry_match = rob_val(i) && maskMatch(io.br_unit.brinfo.mask, br_mask)

         //kill instruction if mispredict & br mask match
         when (io.br_unit.brinfo.valid && io.br_unit.brinfo.mispredict && entry_match)
         {
            rob_val(i) := Bool(false)
            rob_uop(UInt(i)).inst := BUBBLE
         }
         .elsewhen (io.br_unit.brinfo.valid && !io.br_unit.brinfo.mispredict && entry_match)
         {
            // clear speculation bit even on correct speculation
            rob_uop(i).br_mask := (br_mask & ~io.br_unit.brinfo.mask)
         }
      }

      // -----------------------------------------------
      // Commit
      when (will_commit(w))
      {
         rob_val(rob_head) := Bool(false)
      }

      // -----------------------------------------------
      // Outputs
      rob_head_vals(w)     := rob_val(rob_head)
      rob_head_fflags(w)   := rob_fflags(rob_head)
      rob_head_is_store(w) := rob_uop(rob_head).is_store
      rob_head_is_load(w)  := rob_uop(rob_head).is_load
      rob_brt_vals(w)      := rob_val(WrapInc(GetRowIdx(io.get_pc.rob_idx), num_rob_rows))

      // -----------------------------------------------
      // debugging write ports that should not be synthesized
      when (will_commit(w))
      {
         rob_uop(rob_head).inst := BUBBLE
      }
      .elsewhen (rob_state === s_rollback)
      {
         rob_uop(rob_tail).inst := BUBBLE
      }


      //--------------------------------------------------
      // Debug: for debug purposes, track side-effects to all register destinations

      for (i <- 0 until num_wakeup_ports)
      {
         val rob_idx = io.wb_resps(i).bits.uop.rob_idx
         when (io.debug_wb_valids(i) && MatchBank(GetBankIdx(rob_idx)))
         {
            rob_uop(GetRowIdx(rob_idx)).debug_wdata := io.debug_wb_wdata(i)
         }
         val temp_uop = rob_uop(GetRowIdx(rob_idx))

         assert (!(io.wb_resps(i).valid && MatchBank(GetBankIdx(rob_idx)) &&
                     !rob_val(GetRowIdx(rob_idx))),
                  "ROB writeback occurred to an invalid ROB entry.")
         assert (!(io.wb_resps(i).valid && MatchBank(GetBankIdx(rob_idx)) &&
                  temp_uop.ldst_val && temp_uop.pdst != io.wb_resps(i).bits.uop.pdst),
                  "ROB writeback occurred to the wrong pdst.")
      }
      io.com_uops(w).debug_wdata := rob_uop(rob_head).debug_wdata

      //--------------------------------------------------
      // Debug: handle passing out signals to printf in dpath

      if (DEBUG_PRINTF_ROB)
      {
         for (i <- 0 until num_rob_rows)
         {
            debug_entry(w + i*width).valid := rob_val(i)
            debug_entry(w + i*width).busy := rob_bsy(UInt(i))
            debug_entry(w + i*width).uop := rob_uop(UInt(i))
            debug_entry(w + i*width).uop.pc := rob_pc_hob.read(UInt(i,log2Up(num_rob_rows))) + UInt(w << 2)
            debug_entry(w + i*width).exception := rob_exception(UInt(i))
         }
      }

   } //for (w <- 0 until width)

   // **************************************************************************
   // --------------------------------------------------------------------------
   // **************************************************************************


   // -----------------------------------------------
   // Commit Logic
   // need to take a "can_commit" array, and let the first can_commits commit
   // previous instructions may block the commit of younger instructions in the commit bundle
   // e.g., exception, or (valid && busy).
   // Finally, don't throw an exception if there are instructions in front of
   // it that want to commit (only throw exception when head of the bundle).

   var block_commit = (rob_state != s_normal) && (rob_state != s_wait_till_empty)
   var will_throw_exception = Bool(false)
   var block_xcpt   = Bool(false) // TODO we can relax this constraint, so long
                                  // as we handle committing stores in
                                  // conjuction with exceptions, and exceptions
                                  // with flush_on_commit (I think).

   for (w <- 0 until width)
   {
      will_throw_exception = (can_throw_exception(w) && !block_commit && !block_xcpt) || will_throw_exception

      will_commit(w)       := can_commit(w) && !can_throw_exception(w) && !block_commit
      block_commit         = (rob_head_vals(w) &&
                             (!can_commit(w) || can_throw_exception(w))) | block_commit
      block_xcpt           = will_commit(w)
   }

   // exception must be in the commit bundle
   // Note: exception must be the first valid instruction in the commit bundle
   exception_thrown    := will_throw_exception || io.cxcpt.valid
   val is_mini_exception = io.com_exc_cause === MINI_EXCEPTION_MEM_ORDERING
   io.com_exception    := exception_thrown && !is_mini_exception
   io.com_exc_cause    := r_xcpt_uop.exc_cause
   io.com_handling_exc := exception_thrown  // TODO get rid of com_handling_exc? used to handle loads coming back from the $ probbaly unnecessary

   io.lsu_misspec := Reg(next=exception_thrown && io.com_exc_cause === MINI_EXCEPTION_MEM_ORDERING)
   io.com_badvaddr := Sext(r_xcpt_badvaddr,xLen)

   val refetch_inst = exception_thrown
   io.flush_pc  := rob_pc_hob.read(rob_head) +
                   PriorityMux(rob_head_vals, Range(0,width).map(w => UInt(w << 2))) +
                   Mux(refetch_inst, UInt(0), UInt(4))
   val flush_val = exception_thrown ||
                     (Range(0,width).map{i => io.com_valids(i) && io.com_uops(i).flush_on_commit}).reduce(_|_)

   io.flush_take_pc  := flush_val
   io.flush_pipeline := Reg(next=flush_val)

   // -----------------------------------------------
   // FP Exceptions
   // send fflags bits to the CSRFile to accrue

   val fflags_val = Vec.fill(width) {Bool()}
   val fflags     = Vec.fill(width) {Bits(width=rocket.FPConstants.FLAGS_SZ)}

   for (w <- 0 until width)
   {
      // TODO can I relax the ld/st constraint?
      fflags_val(w) := io.com_valids(w) &&
                       io.com_uops(w).fp_val &&
                      !(io.com_uops(w).is_load || io.com_uops(w).is_store)

      fflags(w) := Mux(fflags_val(w), rob_head_fflags(w), Bits(0))

      assert (!(io.com_valids(w) &&
               !io.com_uops(w).fp_val &&
               rob_head_fflags(w) != Bits(0)),
               "Committed non-FP instruction has non-zero exception bits.")
      assert (!(io.com_valids(w) &&
               io.com_uops(w).fp_val &&
               (io.com_uops(w).is_load || io.com_uops(w).is_store) &&
               rob_head_fflags(w) != Bits(0)),
               "Committed FP load or store has non-zero exception bits.")
   }
   io.com_fflags_val := fflags_val.reduce(_|_)
   io.com_fflags     := fflags.reduce(_|_)

   // -----------------------------------------------
   // Exception Tracking Logic
   // only store the oldest exception, since only one can happen!

   // is i0 older than i1? (closest to zero)
   // provide the tail_ptr to the queue
   def IsOlder(i0: UInt, i1: UInt, tail: UInt) =
   {
      (Cat(i0 < tail, i0) < Cat(i1 < tail, i1))
   }

   val next_xcpt_uop = new MicroOp()
   next_xcpt_uop := r_xcpt_uop
   val dis_xcpts = Vec.fill(width) {Bool()}
   for (i <- 0 until width)
   {
      dis_xcpts(i) := io.dis_mask(i) && io.dis_uops(i).exception
   }

   when (!(io.flush_pipeline || exception_thrown) && rob_state != s_rollback)
   {
      when (io.lxcpt.valid || io.bxcpt.valid)
      {
         val new_xcpt_uop = Mux(io.lxcpt.valid, io.lxcpt.bits.uop, io.bxcpt.bits.uop)
         when (!r_xcpt_val || IsOlder(new_xcpt_uop.rob_idx, r_xcpt_uop.rob_idx, rob_tail_idx))
         {
            r_xcpt_val              := Bool(true)
            next_xcpt_uop           := new_xcpt_uop
            next_xcpt_uop.exc_cause := Mux(io.lxcpt.valid, io.lxcpt.bits.cause, io.bxcpt.bits.cause)
            r_xcpt_badvaddr         := Mux(io.lxcpt.valid, io.lxcpt.bits.badvaddr, io.bxcpt.bits.badvaddr)
         }
      }
      .elsewhen (!r_xcpt_val && dis_xcpts.reduce(_|_))
      {
         val idx = dis_xcpts.indexWhere{i: Bool => i}

         // if no exception yet, dispatch exception wins
         r_xcpt_val      := Bool(true)
         next_xcpt_uop   := io.dis_uops(idx)
         r_xcpt_badvaddr := io.dis_uops(0).pc + (idx << UInt(2))
      }
   }

   r_xcpt_uop         := next_xcpt_uop
   r_xcpt_uop.br_mask := GetNewBrMask(io.br_unit.brinfo, next_xcpt_uop)
   when (io.flush_pipeline || IsKilledByBranch(io.br_unit.brinfo, next_xcpt_uop))
   {
      r_xcpt_val := Bool(false)
   }

   assert (!(exception_thrown && !io.cxcpt.valid && !r_xcpt_val),
      "ROB trying to throw an exception, but it doesn't have a valid xcpt_cause")

// not possible to stop all xcpts coming in, since the flush signal goes out a cycle later
//   assert (!(rob_state === s_rollback && (io.lxcpt.valid || io.bxcpt.valid)),
//      "Exception incoming during rollback - exception should have been killed.")

   // -----------------------------------------------
   // ROB Head Logic

   // update when committed ALL valid instructions in commit_bundle
   when ((io.com_valids.toBits != Bits(0)) && ((will_commit.toBits ^ rob_head_vals.toBits) === Bits(0)))
   {
      rob_head := WrapInc(rob_head, num_rob_rows)
   }

   // -----------------------------------------------
   // ROB Tail Logic

   when (rob_state === s_rollback && rob_tail != rob_head)
   {
      rob_tail := WrapDec(rob_tail, num_rob_rows)
   }
   .elsewhen (io.br_unit.brinfo.valid && io.br_unit.brinfo.mispredict)
   {
      rob_tail := WrapInc(GetRowIdx(io.br_unit.brinfo.rob_idx), num_rob_rows)
   }
   .elsewhen (io.dis_mask.toBits != Bits(0))
   {
      rob_tail := WrapInc(rob_tail, num_rob_rows)
   }
   // assert !(rob_tail >= (num_rob_entries/width))

   if (params(EnableCommitMapTable))
   {
      when (Reg(next=exception_thrown))
      {
         rob_tail := UInt(0)
         rob_head := UInt(0)
      }
   }

   // -----------------------------------------------
   // Full Logic

   // TODO can we let the ROB fill up completely?
   // can we track "maybe_full"?
   // maybe full is reset on branch mispredict
   // ALSO must handle xcpt tail/age logic if we do this!
   // also must handle rob_pc valid logic.
   val full = WrapInc(rob_tail, num_rob_rows) === rob_head

   io.empty := rob_head === rob_tail

   io.curr_rob_tail := rob_tail


   io.ready := (rob_state === s_normal) && !full

   //-----------------------------------------------
   //-----------------------------------------------
   //-----------------------------------------------

   // ROB FSM
   if (!params(EnableCommitMapTable))
   {
       switch (rob_state)
      {
         is (s_reset)
         {
            rob_state := s_normal
         }
         is (s_normal)
         {
            when (exception_thrown)
            {
               rob_state := s_rollback
            }
            .otherwise
            {
               for (w <- 0 until width)
               {
                  when (io.dis_mask(w) && io.dis_uops(w).is_unique)
                  {
                     rob_state := s_wait_till_empty
                  }
               }
            }
         }
         is (s_rollback)
         {
            when (rob_tail  === rob_head)
            {
               rob_state := s_normal
            }
         }
         is (s_wait_till_empty)
         {
            when (exception_thrown)
            {
               rob_state := s_rollback
            }
            .elsewhen (rob_tail === rob_head)
            {
               rob_state := s_normal
            }
         }
      }
   }
   else
   {
      switch (rob_state)
      {
         is (s_reset)
         {
            rob_state := s_normal
         }
         is (s_normal)
         {
            when (exception_thrown)
            {
               ;
//               rob_state := s_rollback
            }
            .otherwise
            {
               for (w <- 0 until width)
               {
                  when (io.dis_mask(w) && io.dis_uops(w).is_unique)
                  {
                     rob_state := s_wait_till_empty
                  }
               }
            }
         }
         is (s_rollback)
         {
            when (rob_tail  === rob_head)
            {
               rob_state := s_normal
            }
         }
         is (s_wait_till_empty)
         {
            when (exception_thrown)
            {
               ; //rob_state := s_rollback
            }
            .elsewhen (rob_tail === rob_head)
            {
               rob_state := s_normal
            }
         }
      }
   }


   // -----------------------------------------------
   // Outputs

   for (w <- 0 until width)
   {
      // tell LSU it is ready to its stores and loads
      io.com_st_mask(w) := io.com_valids(w) && rob_head_is_store(w)
      io.com_ld_mask(w) := io.com_valids(w) && rob_head_is_load(w)
   }

   //--------------------------------------------------
   // Handle passing out signals to printf in dpath

   io.debug.state    := rob_state
   io.debug.rob_head := rob_head
   io.debug.xcpt_val := r_xcpt_val
   io.debug.xcpt_uop := r_xcpt_uop
   io.debug.xcpt_badvaddr := r_xcpt_badvaddr

   // this object holds the high-order bits of the PC of each ROB row
   // PCs are stored as vaddrBits+1 in size, but extended out to xLen when read
   class RobPCs(width: Int, num_rob_rows: Int)
   {
      val pc_shift = if (width == 1) 2 else (log2Up(width) + 2)
      val pc_hob_width = (vaddrBits+1) - pc_shift

      // bank this so we only need 1 read port to handle branches, which read
      // row X and row X+1
      val bank0 = Mem(UInt(width=pc_hob_width), ceil(num_rob_rows/2).toInt)
      val bank1 = Mem(UInt(width=pc_hob_width), ceil(num_rob_rows/2).toInt)

      // takes rob_row_idx, returns PC (with low-order bits zeroed out)
      def  read (row_idx: UInt) =
      {
         val rdata = Bits(width=xLen)
         rdata := bank0(row_idx >> UInt(1)) << UInt(pc_shift)
         // damn chisel demands a "default"
         when (row_idx(0))
         {
            rdata := bank1(row_idx >> UInt(1)) << UInt(pc_shift)
         }
         Sext(rdata(vaddrBits,0), xLen)
      }

      // returns the row_idx and row_idx+1 PCs (lob zeroed out)
      def read2 (row_idx: UInt) =
      {
         // addr0, left shifted by 1 (makes wrap around logic easier)
         val addr0_ls1 = Mux(row_idx(0), WrapInc(row_idx >> UInt(1), num_rob_rows/2),
                                         row_idx >> UInt(1))
         val data0 = bank0(addr0_ls1) << UInt(pc_shift)
         val data1 = bank1(row_idx >> UInt(1)) << UInt(pc_shift)

         val curr_pc = UInt(width = xLen)
         val next_pc = UInt(width = xLen)
         curr_pc := Mux(row_idx(0), data1, data0)
         next_pc := Mux(row_idx(0), data0, data1)
         val curr_pc_ext = Sext(curr_pc(vaddrBits,0), xLen)
         val next_pc_ext = Sext(next_pc(vaddrBits,0), xLen)

         (curr_pc_ext, next_pc_ext)
      }

      // takes rob_row_idx, write in PC (with low-order bits zeroed out)
      def write (waddr_row: UInt, data: UInt) =
      {
         val data_in = data(vaddrBits,0) >> UInt(pc_shift)
         when (waddr_row(0))
         {
            bank1(waddr_row >> UInt(1)) := data_in
         }
         .otherwise
         {
            bank0(waddr_row >> UInt(1)) := data_in
         }
      }
   }

   printf("  RobXcpt[%s%x r:%d b:%x bva:0x%x]\n"
            , Mux(io.debug.xcpt_val, Str("E"),Str("-"))
            , io.debug.xcpt_uop.exc_cause
            , io.debug.xcpt_uop.rob_idx
            , io.debug.xcpt_uop.br_mask
            , io.debug.xcpt_badvaddr
            )

   if (DEBUG_PRINTF_ROB)
   {
      var r_idx = 0
      for (i <- 0 until (NUM_ROB_ENTRIES/COMMIT_WIDTH))
      {
//            rob[ 0]           (  )(  ) 0x00002000 [ -                       ][unknown                  ]    ,   (d:X p 1, bm:0 - sdt: 0) (d:- p 3, bm:f - sdt:60)
//            rob[ 1]           (  )(B ) 0xc71cb68e [flw     fa3, -961(s11)   ][ -                       ] E31,   (d:- p22, bm:e T sdt:57) (d:- p 0, bm:0 - sdt: 0)
//            rob[ 2] HEAD ---> (vv)( b) 0x00002008 [lui     ra, 0x2          ][addi    ra, ra, 704      ]    ,   (d:x p 2, bm:1 - sdt: 0) (d:x p 3, bm:1 - sdt: 2)
//            rob[ 3]           (vv)(bb) 0x00002010 [lw      s1, 0(ra)        ][lui     t3, 0xff0        ]    ,   (d:x p 4, bm:0 - sdt: 0) (d:x p 5, bm:0 - sdt: 0)
//            rob[ 4]      TL-> (v )(b ) 0x00002018 [addiw   t3, t3, 255      ][li      t2, 2            ]    ,   (d:x p 6, bm:0 - sdt: 5) (d:x p 7, bm:0 - sdt: 0)

         val row = if (COMMIT_WIDTH == 1) r_idx else (r_idx >> log2Up(COMMIT_WIDTH))
         val r_head = rob_head
         val r_tail = rob_tail

         printf("    rob[%d] %s ("
            , UInt(row, ROB_ADDR_SZ)
            , Mux(r_head === UInt(row) && r_tail === UInt(row), Str("HEAD,TL->"),
              Mux(r_head === UInt(row), Str("HEAD --->"),
              Mux(r_tail === UInt(row), Str("     TL->"),
                                        Str(" "))))
            )

         if (COMMIT_WIDTH == 1)
         {
            printf("(%s)(%s) 0x%x [DASM(%x)] %s "
               , Mux(debug_entry(r_idx+0).valid, Str(b_cyn + "V" + end), Str(grn + " " + end))
               , Mux(debug_entry(r_idx+0).busy, Str(b_ylw + "B" + end),  Str(grn + " " + end))
               , debug_entry(r_idx+0).uop.pc(31,0)
               , debug_entry(r_idx+0).uop.inst
               , Mux(debug_entry(r_idx+0).exception, Str("E"), Str("-"))
               )
         }
         else if (COMMIT_WIDTH == 2)
         {
            val row_is_val = debug_entry(r_idx+0).valid || debug_entry(r_idx+1).valid
            printf("(%s%s)(%s%s) 0x%x %x [%sDASM(%x)][DASM(%x)" + end + "] %s,%s "
               , Mux(debug_entry(r_idx+0).valid, Str(b_cyn + "V" + end), Str(grn + " " + end))
               , Mux(debug_entry(r_idx+1).valid, Str(b_cyn + "V" + end), Str(grn + " " + end))
               , Mux(debug_entry(r_idx+0).busy,  Str(b_ylw + "B" + end), Str(grn + " " + end))
               , Mux(debug_entry(r_idx+1).busy,  Str(b_ylw + "B" + end), Str(grn + " " + end))
               , debug_entry(r_idx+0).uop.pc(31,0)
               , debug_entry(r_idx+1).uop.pc(15,0)
               , Mux(r_head === UInt(row) && row_is_val, Str(b_red),
                 Mux(row_is_val                        , Str(b_cyn),
                                                         Str(grn)))
               , debug_entry(r_idx+0).uop.inst
               , debug_entry(r_idx+1).uop.inst
               , Mux(debug_entry(r_idx+0).exception, Str("E"), Str("-"))
               , Mux(debug_entry(r_idx+1).exception, Str("E"), Str("-"))
               )
         }
         else if (COMMIT_WIDTH == 4)
         {
            val row_is_val = debug_entry(r_idx+0).valid || debug_entry(r_idx+1).valid || debug_entry(r_idx+2).valid || debug_entry(r_idx+3).valid
            printf("(%s%s%s%s)(%s%s%s%s) 0x%x %x %x %x [%sDASM(%x)][DASM(%x)][DASM(%x)][DASM(%x)" + end + "]%s%s%s%s"
               , Mux(debug_entry(r_idx+0).valid, Str(b_cyn + "V" + end), Str(grn + " " + end))
               , Mux(debug_entry(r_idx+1).valid, Str(b_cyn + "V" + end), Str(grn + " " + end))
               , Mux(debug_entry(r_idx+2).valid, Str(b_cyn + "V" + end), Str(grn + " " + end))
               , Mux(debug_entry(r_idx+3).valid, Str(b_cyn + "V" + end), Str(grn + " " + end))
               , Mux(debug_entry(r_idx+0).busy,  Str(b_ylw + "B" + end), Str(grn + " " + end))
               , Mux(debug_entry(r_idx+1).busy,  Str(b_ylw + "B" + end), Str(grn + " " + end))
               , Mux(debug_entry(r_idx+2).busy,  Str(b_ylw + "B" + end), Str(grn + " " + end))
               , Mux(debug_entry(r_idx+3).busy,  Str(b_ylw + "B" + end), Str(grn + " " + end))
               , debug_entry(r_idx+0).uop.pc(23,0)
               , debug_entry(r_idx+1).uop.pc(15,0)
               , debug_entry(r_idx+2).uop.pc(15,0)
               , debug_entry(r_idx+3).uop.pc(15,0)
               , Mux(r_head === UInt(row) && row_is_val, Str(b_red),
                 Mux(row_is_val                        , Str(b_cyn), Str(grn)))
               , debug_entry(r_idx+0).uop.inst
               , debug_entry(r_idx+1).uop.inst
               , debug_entry(r_idx+2).uop.inst
               , debug_entry(r_idx+3).uop.inst
               , Mux(debug_entry(r_idx+0).exception, Str("E"), Str("-"))
               , Mux(debug_entry(r_idx+1).exception, Str("E"), Str("-"))
               , Mux(debug_entry(r_idx+2).exception, Str("E"), Str("-"))
               , Mux(debug_entry(r_idx+3).exception, Str("E"), Str("-"))
               )
         }
         else
         {
            println("  BOOM's Chisel printf does not support commit_width >= " + COMMIT_WIDTH)
         }

         var temp_idx = r_idx
         for (w <- 0 until COMMIT_WIDTH)
         {
            printf("(d:%s p%d, bm:%x sdt:%d) "
               , Mux(debug_entry(temp_idx).uop.dst_rtype === RT_FIX, Str("X"),
                 Mux(debug_entry(temp_idx).uop.dst_rtype === RT_PAS, Str("C"),
                 Mux(debug_entry(temp_idx).uop.dst_rtype === RT_FLT, Str("f"),
                 Mux(debug_entry(temp_idx).uop.dst_rtype === RT_X, Str("-"), Str("?")))))
               , debug_entry    (temp_idx).uop.pdst
               , debug_entry    (temp_idx).uop.br_mask
               , debug_entry    (temp_idx).uop.stale_pdst
            )
            temp_idx = temp_idx + 1
         }

         r_idx = r_idx + COMMIT_WIDTH

         printf("\n")
      }
   }

}

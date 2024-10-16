## How DMA Read Processed

DMA Read는 `mvin` 명령어를 처리하기 위해 실행된다. `mvin rs1, rs2` 는 Scratchpad[rs2] <= DRAM[Translate[rs1]] 을 수행하는데, DMA를 통해 main memory에서 데이터를 읽어오고 그 값을 scratchpad에 쓴다. 자세한 내용은 [ISA](https://github.com/minseongg/gemmini?tab=readme-ov-file#mvin-move-data-from-main-memory-to-scratchpad) 부분을 참고하자.

1. DMA Read Request는 `io.dma.read.req` 포트를 통해 들어온다.

- `io.dma` 포트 정의 (L208-211)

  ```scala
  val dma = new Bundle {
    val read = Flipped(new ScratchpadReadMemIO(local_addr_t, mvin_scale_t_bits))
    val write = Flipped(new ScratchpadWriteMemIO(local_addr_t, accType.getWidth, acc_scale_t_bits))
  }
  ```

  DMA read request는 load controller로부터 들어오는데, DMA를 통해서 main memory의 값을 읽고 그걸 scratchpad에 쓴다. `dmaread` 는 결국 main memory를 read하고, 그걸 spad에 write하는 것.

- `ScratchpadReadMemIO` 타입 정의 (L64-67)

  ```scala
  class ScratchpadReadMemIO[U <: Data](local_addr_t: LocalAddr, scale_t_bits: Int)(implicit p: Parameters) extends CoreBundle {
    val req = Decoupled(new ScratchpadMemReadRequest(local_addr_t, scale_t_bits))
    val resp = Flipped(Valid(new ScratchpadMemReadResponse))
  }
  ```

  입력 (`req`) 은 valid-ready 인터페이스이고, 출력 (`resp`) 은 valid 인터페이스인 것을 알 수 있다.

- `ScratchpadMemReadRequest` 타입 정의 (L14-28)

  ```scala
  class ScratchpadMemReadRequest[U <: Data](local_addr_t: LocalAddr, scale_t_bits: Int)(implicit p: Parameters) extends CoreBundle {
    val vaddr = UInt(coreMaxAddrBits.W)
    val laddr = local_addr_t.cloneType

    val cols = UInt(16.W) // TODO don't use a magic number for the width here
    val repeats = UInt(16.W) // TODO don't use a magic number for the width here
    val scale = UInt(scale_t_bits.W)
    val has_acc_bitwidth = Bool()
    val all_zeros = Bool()
    val block_stride = UInt(16.W) // TODO magic numbers
    val pixel_repeats = UInt(8.W) // TODO magic numbers
    val cmd_id = UInt(8.W) // TODO don't use a magic number here
    val status = new MStatus

  }
  ```

  - `laddr`: Local Address
  - `cols`: ??
  - `block_stride`: ??

2. 1에서 `io.dma.read.req` 포트를 통해 들어온 값은, `all_zeros` 필드의 값이 0이 아닌 경우 `read_issue_q` 모듈로, 0인 경우 `zero_writer` 모듈로 들어간다.

- `io.dma.read.req` 포트와 `read_issue_q`, `zero_writer` 모듈 연결 (L315-328)

  ```scala
  read_issue_q.io.enq <> io.dma.read.req

  val zero_writer = Module(new ZeroWriter(config, new ScratchpadMemReadRequest(local_addr_t, mvin_scale_t_bits)))

  when (io.dma.read.req.bits.all_zeros) {
    read_issue_q.io.enq.valid := false.B
    io.dma.read.req.ready := zero_writer.io.req.ready
  }

  zero_writer.io.req.valid := io.dma.read.req.valid && io.dma.read.req.bits.all_zeros
  zero_writer.io.req.bits.laddr := io.dma.read.req.bits.laddr
  zero_writer.io.req.bits.cols := io.dma.read.req.bits.cols
  zero_writer.io.req.bits.block_stride := io.dma.read.req.bits.block_stride
  zero_writer.io.req.bits.tag := io.dma.read.req.bits
  ```

  기본적으로는 `read_issue_q.io.enq` 포트와 `io.dma.read.req` 포트가 연결되지만, `all_zeros` 필드가 `true` 인 경우 `read_issue_q.io.enq.valid` 를 `false` 로 만들고 `zero_writer.io.req` 에 값을 채우는 것을 볼 수 있다.

  > `mvin` 의 `rs1` 이 0인 경우 scratchpad에 무조건 0을 쓰도록 구현되어 있는데, 이게 main memory에 실제로 0이 써져 있어서 그런 건지, 아니면 `rs1` 이 0일때만 특수하게 처리하는 것인지는 모르겠다.

3. 2에서 `read_issue_q` 모듈로 들어온 값은, DMA reader로 가서 값을 읽는다.

- `read_issue_q.io.deq` 포트와 `reader.module` 모듈 연결 (L348-362)

  ```scala
  reader.module.io.req.valid := read_issue_q.io.deq.valid
  read_issue_q.io.deq.ready := reader.module.io.req.ready
  reader.module.io.req.bits.vaddr := read_issue_q.io.deq.bits.vaddr
  reader.module.io.req.bits.spaddr := Mux(read_issue_q.io.deq.bits.laddr.is_acc_addr,
    read_issue_q.io.deq.bits.laddr.full_acc_addr(), read_issue_q.io.deq.bits.laddr.full_sp_addr())
  reader.module.io.req.bits.len := read_issue_q.io.deq.bits.cols
  reader.module.io.req.bits.repeats := read_issue_q.io.deq.bits.repeats
  reader.module.io.req.bits.pixel_repeats := read_issue_q.io.deq.bits.pixel_repeats
  reader.module.io.req.bits.scale := read_issue_q.io.deq.bits.scale
  reader.module.io.req.bits.is_acc := read_issue_q.io.deq.bits.laddr.is_acc_addr
  reader.module.io.req.bits.accumulate := read_issue_q.io.deq.bits.laddr.accumulate
  reader.module.io.req.bits.has_acc_bitwidth := read_issue_q.io.deq.bits.has_acc_bitwidth
  reader.module.io.req.bits.block_stride := read_issue_q.io.deq.bits.block_stride
  reader.module.io.req.bits.status := read_issue_q.io.deq.bits.status
  reader.module.io.req.bits.cmd_id := read_issue_q.io.deq.bits.cmd_id
  ```

  필드 중 `is_acc` 을 이용하여 나중에 spad bank에 쓸지, acc bank에 쓸지 구분한다.

4. 3에서 DMA reader에서 읽은 값은 spad bank에 쓸지, acc bank에 쓸지에 따라 `mvin_scale_in` 와 `mvin_scale_acc_in` 로 갈리고, 각각 `VectorScalarMultiplier` 모듈로 들어간다.

- 코드 (L364-385, L400-411)

  ```scala
  val (mvin_scale_in, mvin_scale_out) = VectorScalarMultiplier(
    config.mvin_scale_args,
    config.inputType, config.meshColumns * config.tileColumns, chiselTypeOf(reader.module.io.resp.bits),
    is_acc = false
  )
  val (mvin_scale_acc_in, mvin_scale_acc_out) = if (mvin_scale_shared) (mvin_scale_in, mvin_scale_out) else (
    VectorScalarMultiplier(
      config.mvin_scale_acc_args,
      config.accType, config.meshColumns * config.tileColumns, chiselTypeOf(reader.module.io.resp.bits),
      is_acc = true
    )
  )

  mvin_scale_in.valid := reader.module.io.resp.valid && (mvin_scale_shared.B || !reader.module.io.resp.bits.is_acc ||
    (reader.module.io.resp.bits.is_acc && !reader.module.io.resp.bits.has_acc_bitwidth))

  mvin_scale_in.bits.in := reader.module.io.resp.bits.data.asTypeOf(chiselTypeOf(mvin_scale_in.bits.in))
  mvin_scale_in.bits.scale := reader.module.io.resp.bits.scale.asTypeOf(mvin_scale_t)
  mvin_scale_in.bits.repeats := reader.module.io.resp.bits.repeats
  mvin_scale_in.bits.pixel_repeats := reader.module.io.resp.bits.pixel_repeats
  mvin_scale_in.bits.last := reader.module.io.resp.bits.last
  mvin_scale_in.bits.tag := reader.module.io.resp.bits
  ```

  ```scala
  if (!mvin_scale_shared) {
    mvin_scale_acc_in.valid := reader.module.io.resp.valid &&
      (reader.module.io.resp.bits.is_acc && reader.module.io.resp.bits.has_acc_bitwidth)
    mvin_scale_acc_in.bits.in := reader.module.io.resp.bits.data.asTypeOf(chiselTypeOf(mvin_scale_acc_in.bits.in))
    mvin_scale_acc_in.bits.scale := reader.module.io.resp.bits.scale.asTypeOf(mvin_scale_acc_t)
    mvin_scale_acc_in.bits.repeats := reader.module.io.resp.bits.repeats
    mvin_scale_acc_in.bits.pixel_repeats := 1.U
    mvin_scale_acc_in.bits.last := reader.module.io.resp.bits.last
    mvin_scale_acc_in.bits.tag := reader.module.io.resp.bits

    mvin_scale_acc_out.ready := false.B
  }
  ```

5. 4에서 spad bank에 써서 `mvin_scale_out` 으로 나온 값은 `PixelRepeater` 로 들어간다.

- 코드 (L387-L398)

  ```scala
  val mvin_scale_pixel_repeater = Module(new PixelRepeater(inputType, local_addr_t, block_cols, aligned_to, mvin_scale_out.bits.tag.cloneType, passthrough = !has_first_layer_optimizations))
  mvin_scale_pixel_repeater.io.req.valid := mvin_scale_out.valid
  mvin_scale_pixel_repeater.io.req.bits.in := mvin_scale_out.bits.out
  mvin_scale_pixel_repeater.io.req.bits.mask := mvin_scale_out.bits.tag.mask take mvin_scale_pixel_repeater.io.req.bits.mask.size
  mvin_scale_pixel_repeater.io.req.bits.laddr := mvin_scale_out.bits.tag.addr.asTypeOf(local_addr_t) + mvin_scale_out.bits.row
  mvin_scale_pixel_repeater.io.req.bits.len := mvin_scale_out.bits.tag.len
  mvin_scale_pixel_repeater.io.req.bits.pixel_repeats := mvin_scale_out.bits.tag.pixel_repeats
  mvin_scale_pixel_repeater.io.req.bits.last := mvin_scale_out.bits.last
  mvin_scale_pixel_repeater.io.req.bits.tag := mvin_scale_out.bits.tag

  mvin_scale_out.ready := mvin_scale_pixel_repeater.io.req.ready
  mvin_scale_pixel_repeater.io.resp.ready := false.B
  ```

6. 2에서 `zero_writer` 모듈로 들어온 값은, `PixelRepeater` 로 들어간다.

- 코드 (L330-343)

  ```scala
  val zero_writer_pixel_repeater = Module(new PixelRepeater(inputType, local_addr_t, block_cols, aligned_to, new ScratchpadMemReadRequest(local_addr_t, mvin_scale_t_bits), passthrough = !has_first_layer_optimizations))
  zero_writer_pixel_repeater.io.req.valid := zero_writer.io.resp.valid
  zero_writer_pixel_repeater.io.req.bits.in := 0.U.asTypeOf(Vec(block_cols, inputType))
  zero_writer_pixel_repeater.io.req.bits.laddr := zero_writer.io.resp.bits.laddr
  zero_writer_pixel_repeater.io.req.bits.len := zero_writer.io.resp.bits.tag.cols
  zero_writer_pixel_repeater.io.req.bits.pixel_repeats := zero_writer.io.resp.bits.tag.pixel_repeats
  zero_writer_pixel_repeater.io.req.bits.last := zero_writer.io.resp.bits.last
  zero_writer_pixel_repeater.io.req.bits.tag := zero_writer.io.resp.bits.tag
  zero_writer_pixel_repeater.io.req.bits.mask := {
    val n = inputType.getWidth / 8
    val mask = zero_writer.io.resp.bits.mask
    val expanded = VecInit(mask.flatMap(e => Seq.fill(n)(e)))
    expanded
  }
  ```

7. (1) 5에서 나온 값, (2) 4에서 acc bank에서 써서 `mvin_scale_acc_out` 으로 나온 값, (3) 6에서 나온 값 중 하나가 선택되어서 `io.dma.read.resp` 포트로 나간다.

- 코드 (L416-436)

  ```scala
  val mvin_scale_finished = mvin_scale_pixel_repeater.io.resp.fire && mvin_scale_pixel_repeater.io.resp.bits.last
  val mvin_scale_acc_finished = mvin_scale_acc_out.fire && mvin_scale_acc_out.bits.last
  val zero_writer_finished = zero_writer_pixel_repeater.io.resp.fire && zero_writer_pixel_repeater.io.resp.bits.last

  val zero_writer_bytes_read = Mux(zero_writer_pixel_repeater.io.resp.bits.laddr.is_acc_addr,
    zero_writer_pixel_repeater.io.resp.bits.tag.cols * (accType.getWidth / 8).U,
    zero_writer_pixel_repeater.io.resp.bits.tag.cols * (inputType.getWidth / 8).U)

  // For DMA read responses, mvin_scale gets first priority, then mvin_scale_acc, and then zero_writer
  io.dma.read.resp.valid := mvin_scale_finished || mvin_scale_acc_finished || zero_writer_finished

  // io.dma.read.resp.bits.cmd_id := MuxCase(zero_writer.io.resp.bits.tag.cmd_id, Seq(
  io.dma.read.resp.bits.cmd_id := MuxCase(zero_writer_pixel_repeater.io.resp.bits.tag.cmd_id, Seq(
    // mvin_scale_finished -> mvin_scale_out.bits.tag.cmd_id,
    mvin_scale_finished -> mvin_scale_pixel_repeater.io.resp.bits.tag.cmd_id,
    mvin_scale_acc_finished -> mvin_scale_acc_out.bits.tag.cmd_id))

  io.dma.read.resp.bits.bytesRead := MuxCase(zero_writer_bytes_read, Seq(
    // mvin_scale_finished -> mvin_scale_out.bits.tag.bytes_read,
    mvin_scale_finished -> mvin_scale_pixel_repeater.io.resp.bits.tag.bytes_read,
    mvin_scale_acc_finished -> mvin_scale_acc_out.bits.tag.bytes_read))
  ```

To be continued...

# Scratchpad

## Meta

이 모듈은 내부적으로 `sp_banks` 개의 `ScratchpadBank` 와 `acc_banks` 개의 `AccumulatorMem` 을 관리하면서, 들어오는 명령에 따라 값을 읽거나 쓴다. 우리가 타겟하는 config에서는 `sp_banks = 4`, `acc_banks = 2` 이다.

이 모듈로 들어오는 명령은 크게 다음 네 가지로 분류할 수 있다.

**1. ExRead (ExecuteController로부터 들어오는 Read 명령)**

- IO 포트는 `io.srams.read`, `io.acc.read_req`, `io.acc.read_resp` 에 대응된다.
- 실제로 계산을 하는 [`matmul.*` 명령어](https://github.com/minseongg/gemmini?tab=readme-ov-file#core-matmul-sequences)를 처리하기 위해 필요.
- 접근 주소에 따라 `ScratchpadBank` 혹은 `AccumulatorMem` 에서 값을 읽음.

**2. ExWrite (ExecuteController로부터 들어오는 Write 명령)**

- IO 포트는 `io.srams.write`, `io.acc.write` 에 대응된다.
- 실제로 계산을 하는 [`matmul.*` 명령어](https://github.com/minseongg/gemmini?tab=readme-ov-file#core-matmul-sequences)를 처리하기 위해 필요.
- 접근 주소에 따라 `ScratchpadBank` 혹은 `AccumulatorMem` 에 값을 씀.

**3. DmaRead (LoadController로부터 들어오는 Write 명령)**

- IO 포트는 `io.dma.read` 에 대응된다.
- Main Memory의 값을 Scratchpad로 가져오는 [`mvin` 명령어](https://github.com/minseongg/gemmini?tab=readme-ov-file#mvin-move-data-from-main-memory-to-scratchpad)를 처리하기 위해 필요. (Scratchpad[rs2] <= DRAM[Translate[rs1]])
- 접근 주소에 따라 `ScratchpadBank` 혹은 `Accumulator` 에 값을 씀.
- **DMA를 통해 Main Memory에서 값을 읽고 ("DmaRead"), 이 값을 Scratchpad에 쓴다 ("Write 명령"). 헷갈리지 않게 주의.**

**4. DmaWrite (StoreController로부터 들어오는 Read 명령)**

- IO 포트는 `io.dma.write` 에 대응된다.
- Scratchpad의 값을 Main Memory로 보내주는 [`mvout` 명령어](https://github.com/minseongg/gemmini?tab=readme-ov-file#mvout-move-data-from-scratchpad-to-l2dram)를 처리하기 위해 필요. (DRAM[Translate[rs1]] <= Scratchpad[rs2])
- 접근 주소에 따라 `ScratchpadBank` 혹은 `Accumulator` 에서 값을 읽음.
- **Scratchpad에서 값을 읽고 ("Read 명령"), 이 값을 DMA를 통해 Main Memory에 쓴다 ("DmaWrite"). 헷갈리지 않게 주의.**

위 명령들을 받기 위해 Scratchpad 모듈과 각 Controller 모듈들을 연결해주는 코드는 Controller.scala의 L249-256에 있다.

```scala
spad.module.io.dma.read <> load_controller.io.dma
spad.module.io.dma.write <> store_controller.io.dma
ex_controller.io.srams.read <> spad.module.io.srams.read
ex_controller.io.srams.write <> spad.module.io.srams.write
spad.module.io.acc.read_req <> ex_controller.io.acc.read_req
ex_controller.io.acc.read_resp <> spad.module.io.acc.read_resp
ex_controller.io.acc.write <> spad.module.io.acc.write
```

## ExRead 명령 처리 흐름

### `ScratchpadBank` 에서 읽는 경우

1. `io.srams.read(i).req` 포트를 통해 Request를 받는다. 여기서 `i` 는 Bank index를 나타낸다.
2. Request에서 받은 주소로 `ScratchpadBank` 에 read request를 보낸다.
3. `ScratchpadBank` 에서 받은 read data를 pipeline에 넣는다.
4. Pipeline에서 나온 값을 `io.srams.read(i).resp` 포트를 통해 Response로 보내준다.

### `AccumulatorMem` 에서 읽는 경우

1. `io.acc.read_req(i)` 포트를 통해 Request를 받는다. 여기서 `i` 는 Bank index를 나타낸다.
2. Request에서 받은 주소로 `AccumulatorMem` 에 read request를 보낸다.
3. ?? (dma write 명령에서 나온 write norm queue 값과 동기화되어서 normalization, scaling을 함. 왜 이게 필요한지는 아직 모르겠음)

## ExWrite 명령 처리 흐름

### `ScratchpadBank` 에 쓰는 경우

1. `io.srams.write(i).req` 포트를 통해 Request를 받는다. 여기서 `i` 는 Bank index를 나타낸다.
2. Request에서 받은 주소로 `ScratchpadBank` 에 write request를 보낸다.

### `AccumulatorMem` 에 쓰는 경우

1. `io.acc.write(i).req` 포트를 통해 Request를 받는다. 여기서 `i` 는 Bank index를 나타낸다.
2. Request에서 받은 주소로 `AccumulatorMem` 에 write request를 보낸다.

## DmaRead 명령 처리 흐름

DmaRead 명령 처리(Scratchpad[rs2] <= DRAM[Translate[rs1]])는 크게 다음 세 부분으로 나뉘어진다:

- Main Memory에서 값 읽기 (Read from Main Memory)
  + StreamRead 모듈에 Read Request를 보내서 Main Memory에서 값을 읽는다.
  + 이때 virtual DRAM address (`rs1`) 이 0인 경우, Main Memory에서 값을 읽지 않고 Scratchpad에 0을 쓰도록 한다. (이게 단순히 최적화인지, 아니면 `rs1 = 0` 에 대한 예외 처리 같은 것인지는 아직 모르겠음)
  + 해당되는 Bank에 Write Request를 보내서 Main Memory에서 읽은 값을 쓰게 한다.
  + Bank에 Write Request를 보냄과 동시에, DmaRead 명령에 대한 Response를 보내줌. 이는 실제로 Bank에 값을 쓰기 전에 완료됨.
- ScratchpadBank / AccumulatorMem에 값 쓰기 (Write to ScratchpadBank / AccumulatorMem)
  + Bank에서 Wrie Request를 처리한다.

<!--
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

  - `vaddr`: Virtual DRAM address (byte addressed) to load into scratchpad
  - `laddr`: Local scratchpad or accumulator address. It contains some information indicates the address is in spad/acc, and the bank index.
  - `cols`: Number of columns to store
  - `block_stride`: Main memory stride set by [`config_mvin` command](https://github.com/minseongg/gemmini?tab=readme-ov-file#config_mvin-configures-the-load-pipeline)
  - ...

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
-->

## DmaWrite 명령 처리 흐름

DmaWrite 명령 처리(DRAM[Translate[rs1]] <= Scratchpad[rs2])는 크게 다음 세 부분으로 나뉘어진다:

- 전처리 (Preprocess)
  + Scratchpad local address (`rs2`) 를 보고 ScratchpadBank와 AccumulatorMem 중 어디에서 읽어야 할지, Bank index는 무엇인지 계산.
  + 해당하는 Bank에 Read Request를 보냄.
  + Bank에 Read Request를 보냄과 동시에, DmaWrite 명령에 대한 Response를 보내줌. 이는 실제로 Bank에서 값을 읽기 전에 완료됨.
- ScratchpadBank / AccumulatorMem에서 값 읽기 (Read from ScratchpadBank / AccumulatorMem):
  + 각 Bank에서는 전처리 과정에서 들어온 Read Request를 받아서 Response를 내놓음.
  + AccumulatorMem에서 값을 읽은 경우 추가적인 Normalization, Scaling 작업을 진행함.
- Main Memory에 값 쓰기 (Write to Main Memory)
  + StreamWriter 모듈에 Write Request를 보내서 Main Memory에 값을 쓴다.

More details: to be continued...

<!--
### 전처리

- 코드 (L270-273)

  ```scala
  // Garbage can immediately fire from dispatch_q -> norm_q
  when (write_dispatch_q.bits.laddr.is_garbage()) {
    write_norm_q.io.enq <> write_dispatch_q
  }
  ```

  - local address가 garbage이면 바로 write norm queue로 보내지고, garbage가 아니면 scratchpad/accumulator에서 값을 읽는다.

### Scratchpad/Accumulator에서 값을 읽는 과정

- Scratchpad쪽 코드 (L462-466)

  ```scala
  // TODO we tie the write dispatch queue's, and write issue queue's, ready and valid signals together here
  val dmawrite = write_dispatch_q.valid && write_norm_q.io.enq.ready &&
    !write_dispatch_q.bits.laddr.is_garbage() &&
    !(bio.write.en && config.sp_singleported.B) &&
    !write_dispatch_q.bits.laddr.is_acc_addr && write_dispatch_q.bits.laddr.sp_bank() === i.U
  ```

  - `write_dispatch_q.valid && write_norm_q.io.enq.ready`: write dispatch queue에서 나와서 write norm queue로 들어갈 수 있어야 scratchpad에서 읽음.
  - `!write_dispatch_q.bits.laddr.is_garbage()`: local address가 garbage이면 scratchpad에서 값을 읽을 필요가 없다.
  - `!(bio.write.en && config.sp_singleported.B)`: scratchpad에 값을 쓰는 것과 동시에 읽으려고 하면 쓰는 게 우선적으로 진행된다.
  - `!write_dispatch_q.bits.laddr.is_acc_addr && write_dispatch_q.bits.laddr.sp_bank() === i.U`: local address가 지금 scratchpad를 읽는 게 맞는지 확인.

- Accumulator쪽 코드 (L656-659)

  ```scala
  // TODO we tie the write dispatch queue's, and write issue queue's, ready and valid signals together here
  val dmawrite = write_dispatch_q.valid && write_norm_q.io.enq.ready &&
    !write_dispatch_q.bits.laddr.is_garbage() &&
    write_dispatch_q.bits.laddr.is_acc_addr && write_dispatch_q.bits.laddr.acc_bank() === i.U
  ```

  - `write_dispatch_q.valid && write_norm_q.io.enq.ready`: write dispatch queue에서 나와서 write norm queue로 들어갈 수 있어야 accumulator에서 읽음.
  - `!write_dispatch_q.bits.laddr.is_garbage()`: local address가 garbage이면 accumulator에서 값을 읽을 필요가 없다.
  - `write_dispatch_q.bits.laddr.is_acc_addr && write_dispatch_q.bits.laddr.acc_bank() === i.U`: local address가 지금 accumulator를 읽는 게 맞는지 확인.

- dma response 보내는 코드 (L479-484, L686-691)

  ```scala
  when (bio.read.req.fire) {
    write_dispatch_q.ready := true.B
    write_norm_q.io.enq.valid := true.B

    io.dma.write.resp.valid := true.B
  }
  ```

  ```scala
  when (bio.read.req.fire) {
    write_dispatch_q.ready := true.B
    write_norm_q.io.enq.valid := true.B

    io.dma.write.resp.valid := true.B
  }
  ```

  - scratchpad나 accumulator에 값을 읽게 되면 write dispatch queue에서 나온 command id와 함께 바로 dma response를 보내준다.
  - 또한 write norm queue에도 값을 쓴다.

### Main memory에 값을 쓰는 과정

write norm queue -> normalizer -> write scale queue -> accumulator scale -> write issue queue -> writer

```scala

```
-->

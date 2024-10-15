## How DMA Read Processed

1. DMA Read Request는 `io.dma.read.req` 포트를 통해 들어온다.

- `io.dma` 포트 정의 (L208-211)

  ```scala
  val dma = new Bundle {
    val read = Flipped(new ScratchpadReadMemIO(local_addr_t, mvin_scale_t_bits))
    val write = Flipped(new ScratchpadWriteMemIO(local_addr_t, accType.getWidth, acc_scale_t_bits))
  }
  ```

  > DMA read와 write는 포트 연결된 것을 보면 TLB와 밀접한 연관이 있는 것 같다.

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

To be continued...

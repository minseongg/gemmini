# ZeroWriter

이 모듈은 `Scratchpad` 모듈에서 `mvin` 명령어를 처리할 때 `rs1` 이 0이 들어온 경우를 위한 작업을 해준다.

## IO

- `IO` 정의 (L32-35)

  ```scala
  val io = IO(new Bundle {
    val req = Flipped(Decoupled(new ZeroWriterReq(local_addr_t, max_cols, tag_t)))
    val resp = Decoupled(new ZeroWriterResp(local_addr_t, block_cols, tag_t))
  })
  ```

  입력 (`req`) 으로 valid-ready 인터페이스를 받아서 출력 (`resp`) 으로 valid-ready 인터페이스를 리턴한다.

- `ZeroWriterReq` 정의 (L9-15)

  ```scala
  class ZeroWriterReq[Tag <: Data](laddr_t: LocalAddr, max_cols: Int, tag_t: Tag) extends Bundle {
    val laddr = laddr_t
    val cols = UInt(log2Up(max_cols+1).W)
    val block_stride = UInt(16.W) // TODO magic number
    val tag = tag_t

  }
  ```

- `ZeroWriterResp` 정의 (L9-15)

  ```scala
  class ZeroWriterResp[Tag <: Data](laddr_t: LocalAddr, block_cols: Int, tag_t: Tag) extends Bundle {
    val laddr = laddr_t.cloneType
    val mask = Vec(block_cols, Bool())
    val last = Bool()
    val tag = tag_t

  }
  ```

## Behavior

입력으로 들어온 명령을 작은 명령들로 쪼개서 출력으로 보내준다.

To be continued...

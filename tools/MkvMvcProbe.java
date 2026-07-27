/*
 * Read-only diagnostic for locating MakeMKV MVC mappings and dependent-view blocks.
 *
 * Run with:
 *   java tools/MkvMvcProbe.java "movie.mkv"
 *   java tools/MkvMvcProbe.java "movie.mkv" "sample.264" 240
 */
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

public final class MkvMvcProbe {
  private static final long SEGMENT = 0x18538067L;
  private static final long TRACKS = 0x1654AE6BL;
  private static final long TRACK_ENTRY = 0xAEL;
  private static final long TRACK_NUMBER = 0xD7L;
  private static final long CODEC_PRIVATE = 0x63A2L;
  private static final long MAX_BLOCK_ADDITION_ID = 0x55EEL;
  private static final long STEREO_MODE = 0x53B8L;
  private static final long BLOCK_ADDITION_MAPPING = 0x41E4L;
  private static final long BLOCK_ADD_ID_TYPE = 0x41E7L;
  private static final long BLOCK_ADD_ID_EXTRA_DATA = 0x41EDL;
  private static final long CLUSTER = 0x1F43B675L;
  private static final long SIMPLE_BLOCK = 0xA3L;
  private static final long BLOCK_GROUP = 0xA0L;
  private static final long BLOCK = 0xA1L;
  private static final long BLOCK_ADDITIONS = 0x75A1L;
  private static final long BLOCK_MORE = 0xA6L;
  private static final long BLOCK_ADD_ID = 0xEEL;
  private static final long BLOCK_ADDITIONAL = 0xA5L;
  private static final long MVCC = 0x6D766343L;

  private final RandomAccessFile file;
  private final OutputStream annexBOutput;
  private final int maxExtractedVideoBlocks;
  private final Set<Long> mvcTrackNumbers = new HashSet<>();
  private long currentTrackNumber = -1;
  private long currentTrackMaxAdditionId;
  private long currentStereoMode = -1;
  private boolean currentTrackHasMvcMapping;
  private int currentMvcConfigSize;
  private byte[] currentCodecPrivate;
  private byte[] currentMvcConfig;
  private int nalUnitLengthFieldLength = 4;
  private long currentBlockTrackNumber = -1;
  private long currentBlockAdditionalId = 1;
  private long clusterCount;
  private boolean foundDependentView;
  private long mvcVideoBlocksInspected;
  private int extractedVideoBlocks;

  private MkvMvcProbe(Path path, Path annexBPath, int maxExtractedVideoBlocks)
      throws IOException {
    file = new RandomAccessFile(path.toFile(), "r");
    annexBOutput =
        annexBPath == null
            ? null
            : new BufferedOutputStream(Files.newOutputStream(annexBPath));
    this.maxExtractedVideoBlocks = maxExtractedVideoBlocks;
  }

  public static void main(String[] args) throws Exception {
    if (args.length != 1 && args.length != 3) {
      throw new IllegalArgumentException(
          "Expected: <movie.mkv> [<sample.264> <max-video-blocks>]");
    }
    Path path = Path.of(args[0]);
    Path annexBPath = args.length == 3 ? Path.of(args[1]) : null;
    int maxExtractedVideoBlocks = args.length == 3 ? Integer.parseInt(args[2]) : 0;
    if (args.length == 3 && maxExtractedVideoBlocks < 1) {
      throw new IllegalArgumentException("max-video-blocks must be positive.");
    }
    try (RandomAccessFile ignored = new RandomAccessFile(path.toFile(), "r")) {
      // Fail early with a clear path/permission error.
    }
    MkvMvcProbe probe = new MkvMvcProbe(path, annexBPath, maxExtractedVideoBlocks);
    try {
      probe.walk(0, probe.file.length(), 0);
      if (probe.annexBOutput != null) {
        probe.annexBOutput.flush();
      }
      System.out.println(
          "RESULT mvcTracks="
              + probe.mvcTrackNumbers
              + " clustersScanned="
              + probe.clusterCount
              + " dependentViewFound="
              + probe.foundDependentView
              + " extractedVideoBlocks="
              + probe.extractedVideoBlocks);
    } finally {
      if (probe.annexBOutput != null) {
        probe.annexBOutput.close();
      }
      probe.file.close();
    }
  }

  private void walk(long start, long end, long parentId) throws IOException {
    long position = start;
    while (position < end && !shouldStop()) {
      Header header = readHeader(position, end);
      if (header == null) {
        return;
      }
      long elementEnd =
          header.unknownSize ? end : Math.min(end, safeAdd(header.dataStart, header.size));
      long id = header.id;

      if (id == SEGMENT || id == TRACKS || id == BLOCK_ADDITION_MAPPING
          || id == BLOCK_ADDITIONS || id == BLOCK_MORE) {
        walk(header.dataStart, elementEnd, id);
      } else if (id == TRACK_ENTRY) {
        beginTrack();
        walk(header.dataStart, elementEnd, id);
        endTrack();
      } else if (id == CLUSTER) {
        clusterCount++;
        walk(header.dataStart, elementEnd, id);
      } else if (id == BLOCK_GROUP) {
        beginBlockGroup();
        walk(header.dataStart, elementEnd, id);
      } else if (parentId == TRACK_ENTRY && id == TRACK_NUMBER) {
        currentTrackNumber = readUnsigned(header);
      } else if (parentId == TRACK_ENTRY && id == CODEC_PRIVATE) {
        currentCodecPrivate = readBytes(header);
      } else if (parentId == TRACK_ENTRY && id == MAX_BLOCK_ADDITION_ID) {
        currentTrackMaxAdditionId = readUnsigned(header);
      } else if (parentId == TRACK_ENTRY && id == STEREO_MODE) {
        currentStereoMode = readUnsigned(header);
      } else if (parentId == BLOCK_ADDITION_MAPPING && id == BLOCK_ADD_ID_TYPE) {
        currentTrackHasMvcMapping = readUnsigned(header) == MVCC;
      } else if (parentId == BLOCK_ADDITION_MAPPING && id == BLOCK_ADD_ID_EXTRA_DATA
          && currentTrackHasMvcMapping) {
        currentMvcConfigSize = Math.toIntExact(header.size);
        currentMvcConfig = readBytes(header);
      } else if (parentId == BLOCK_GROUP && id == BLOCK) {
        currentBlockTrackNumber = readVintValue(header.dataStart, elementEnd);
        inspectAvcBlock(header, currentBlockTrackNumber);
      } else if (parentId == CLUSTER && id == SIMPLE_BLOCK) {
        long blockTrackNumber = readVintValue(header.dataStart, elementEnd);
        inspectAvcBlock(header, blockTrackNumber);
      } else if (parentId == BLOCK_MORE && id == BLOCK_ADD_ID) {
        currentBlockAdditionalId = readUnsigned(header);
      } else if (parentId == BLOCK_MORE && id == BLOCK_ADDITIONAL
          && mvcTrackNumbers.contains(currentBlockTrackNumber)
          && currentBlockAdditionalId == 1) {
        foundDependentView = true;
        System.out.println(
            "MVC_DEPENDENT_VIEW track="
                + currentBlockTrackNumber
                + " cluster="
                + clusterCount
                + " fileOffset="
                + header.dataStart
                + " bytes="
                + header.size);
      }

      if (elementEnd <= position) {
        throw new IOException("Invalid element length at file offset " + position);
      }
      position = elementEnd;
    }
  }

  private void beginTrack() {
    currentTrackNumber = -1;
    currentTrackMaxAdditionId = 0;
    currentStereoMode = -1;
    currentTrackHasMvcMapping = false;
    currentMvcConfigSize = 0;
    currentCodecPrivate = null;
    currentMvcConfig = null;
  }

  private void endTrack() throws IOException {
    if (currentTrackHasMvcMapping && currentTrackNumber >= 0) {
      mvcTrackNumbers.add(currentTrackNumber);
      if (annexBOutput != null) {
        writeDecoderConfiguration();
      }
      System.out.println(
          "MVC_TRACK track="
              + currentTrackNumber
              + " maxBlockAdditionId="
              + currentTrackMaxAdditionId
              + " stereoMode="
              + currentStereoMode
              + " mvcConfigBytes="
              + currentMvcConfigSize);
    }
  }

  private void beginBlockGroup() {
    currentBlockTrackNumber = -1;
    currentBlockAdditionalId = 1;
  }

  private void inspectAvcBlock(Header header, long trackNumber) throws IOException {
    if (!mvcTrackNumbers.contains(trackNumber)
        || (annexBOutput == null && mvcVideoBlocksInspected++ >= 200)) {
      return;
    }
    file.seek(header.dataStart);
    int firstTrackByte = file.readUnsignedByte();
    int trackNumberLength = vintLength(firstTrackByte);
    file.skipBytes(trackNumberLength - 1);
    file.skipBytes(2); // Relative timestamp.
    int flags = file.readUnsignedByte();
    if ((flags & 0x06) != 0) {
      return; // Video in the test file is expected to be unlaced.
    }
    long position = header.dataStart + trackNumberLength + 3;
    long end = safeAdd(header.dataStart, header.size);
    boolean containsMvcNal = false;
    while (position + nalUnitLengthFieldLength <= end) {
      file.seek(position);
      long nalSize = 0;
      for (int i = 0; i < nalUnitLengthFieldLength; i++) {
        nalSize = (nalSize << 8) | file.readUnsignedByte();
      }
      position += nalUnitLengthFieldLength;
      if (nalSize < 1 || nalSize > end - position) {
        return;
      }
      file.seek(position);
      int nalType = file.readUnsignedByte() & 0x1F;
      if (nalType == 14 || nalType == 20) {
        containsMvcNal = true;
        if (!foundDependentView) {
          foundDependentView = true;
          System.out.println(
              "MVC_NAL track="
                  + trackNumber
                  + " cluster="
                  + clusterCount
                  + " fileOffset="
                  + position
                  + " nalType="
                  + nalType
                  + " bytes="
                  + nalSize);
        }
      }
      if (annexBOutput != null) {
        writeStartCode();
        copyToOutput(position, Math.toIntExact(nalSize));
      }
      position += nalSize;
    }
    if (annexBOutput != null) {
      extractedVideoBlocks++;
      if (containsMvcNal && extractedVideoBlocks == 1) {
        System.out.println("First extracted access unit contains MVC data.");
      }
    }
  }

  private boolean shouldStop() {
    return annexBOutput == null
        ? foundDependentView
        : extractedVideoBlocks >= maxExtractedVideoBlocks;
  }

  private void writeDecoderConfiguration() throws IOException {
    if (currentCodecPrivate == null || currentMvcConfig == null) {
      throw new IOException("MVC track is missing AVC or MVC decoder configuration.");
    }
    nalUnitLengthFieldLength = (currentCodecPrivate[4] & 0x03) + 1;
    writeDecoderConfigurationRecord(currentCodecPrivate, 0x1F, "avcC");
    int mvcLengthFieldLength = (currentMvcConfig[4] & 0x03) + 1;
    if (mvcLengthFieldLength != nalUnitLengthFieldLength) {
      throw new IOException(
          "avcC/mvcC NAL length fields disagree: "
              + nalUnitLengthFieldLength
              + " vs "
              + mvcLengthFieldLength);
    }
    writeDecoderConfigurationRecord(currentMvcConfig, 0x7F, "mvcC");
  }

  private void writeDecoderConfigurationRecord(byte[] record, int sequenceCountMask, String label)
      throws IOException {
    if (record.length < 7 || record[0] != 1) {
      throw new IOException("Malformed " + label + " decoder configuration.");
    }
    int position = 5;
    int sequenceCount = record[position++] & sequenceCountMask;
    int parameterSetCount = 0;
    for (int i = 0; i < sequenceCount; i++) {
      int size = readUnsignedShort(record, position);
      position += 2;
      writeParameterSet(record, position, size, label);
      position += size;
      parameterSetCount++;
    }
    if (position >= record.length) {
      throw new IOException("Truncated " + label + " before picture parameter sets.");
    }
    int pictureCount = record[position++] & 0xFF;
    for (int i = 0; i < pictureCount; i++) {
      int size = readUnsignedShort(record, position);
      position += 2;
      writeParameterSet(record, position, size, label);
      position += size;
      parameterSetCount++;
    }
    System.out.println(
        "ANNEX_B_CONFIG " + label + " parameterSets=" + parameterSetCount);
  }

  private void writeParameterSet(byte[] record, int position, int size, String label)
      throws IOException {
    if (size < 1 || position < 0 || position + size > record.length) {
      throw new IOException("Invalid parameter-set length in " + label + ".");
    }
    writeStartCode();
    annexBOutput.write(record, position, size);
  }

  private static int readUnsignedShort(byte[] data, int position) throws IOException {
    if (position < 0 || position + 2 > data.length) {
      throw new IOException("Truncated decoder configuration.");
    }
    return ((data[position] & 0xFF) << 8) | (data[position + 1] & 0xFF);
  }

  private void writeStartCode() throws IOException {
    annexBOutput.write(new byte[] {0, 0, 0, 1});
  }

  private void copyToOutput(long position, int size) throws IOException {
    byte[] buffer = new byte[Math.min(size, 64 * 1024)];
    file.seek(position);
    int remaining = size;
    while (remaining > 0) {
      int count = file.read(buffer, 0, Math.min(buffer.length, remaining));
      if (count < 0) {
        throw new IOException("Unexpected end of MKV sample.");
      }
      annexBOutput.write(buffer, 0, count);
      remaining -= count;
    }
  }

  private byte[] readBytes(Header header) throws IOException {
    byte[] data = new byte[Math.toIntExact(header.size)];
    file.seek(header.dataStart);
    file.readFully(data);
    return data;
  }

  private long readUnsigned(Header header) throws IOException {
    if (header.size < 1 || header.size > 8) {
      throw new IOException("Invalid unsigned integer size at " + header.dataStart);
    }
    file.seek(header.dataStart);
    long value = 0;
    for (int i = 0; i < header.size; i++) {
      value = (value << 8) | file.readUnsignedByte();
    }
    return value;
  }

  private long readVintValue(long position, long end) throws IOException {
    file.seek(position);
    int first = file.readUnsignedByte();
    int length = vintLength(first);
    if (position + length > end) {
      throw new IOException("Truncated variable integer at " + position);
    }
    long value = first & (0xFF >>> length);
    for (int i = 1; i < length; i++) {
      value = (value << 8) | file.readUnsignedByte();
    }
    return value;
  }

  private Header readHeader(long position, long end) throws IOException {
    if (position >= end) {
      return null;
    }
    file.seek(position);
    int firstId = file.read();
    if (firstId < 0) {
      return null;
    }
    int idLength = vintLength(firstId);
    long id = firstId;
    for (int i = 1; i < idLength; i++) {
      id = (id << 8) | file.readUnsignedByte();
    }

    int firstSize = file.readUnsignedByte();
    int sizeLength = vintLength(firstSize);
    long size = firstSize & (0xFF >>> sizeLength);
    boolean unknownSize = size == (0xFF >>> sizeLength);
    for (int i = 1; i < sizeLength; i++) {
      int next = file.readUnsignedByte();
      size = (size << 8) | next;
      unknownSize &= next == 0xFF;
    }
    long dataStart = position + idLength + sizeLength;
    if (dataStart > end) {
      throw new IOException("Truncated element header at " + position);
    }
    return new Header(id, size, dataStart, unknownSize);
  }

  private static int vintLength(int firstByte) throws IOException {
    for (int length = 1; length <= 8; length++) {
      if ((firstByte & (0x80 >>> (length - 1))) != 0) {
        return length;
      }
    }
    throw new IOException("Invalid EBML variable integer");
  }

  private static long safeAdd(long left, long right) {
    return right > Long.MAX_VALUE - left ? Long.MAX_VALUE : left + right;
  }

  private record Header(long id, long size, long dataStart, boolean unknownSize) {}
}

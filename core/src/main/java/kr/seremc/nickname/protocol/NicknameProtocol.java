package kr.seremc.nickname.protocol;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Velocity와 Paper 브리지 사이의 신뢰 경계입니다. 모든 packet은 magic 값, 버전, UUID, 필드 수와 UTF-8 바이트 길이를 검증해 손상된
 * plugin message를 거절합니다.
 */
public final class NicknameProtocol {
  public static final String CHANNEL = "customnickname:main";
  private static final int MAGIC = 0x434E4943,
      MAX_PACKET = 65535,
      MAX_FIELDS = 32,
      MAX_FIELD_BYTES = 16384;
  private static final short VERSION = 1;

  private NicknameProtocol() {}

  /** 메시지 방향은 Velocity plugin message 처리기와 Paper 브리지 처리기에서 각각 allow-list로 제한한다. */
  public enum Type {
    PROFILE_SYNC,
    PROFILE_REQUEST,
    TICKET_ITEM_CREATE,
    TICKET_USE_REQUEST,
    TICKET_USE,
    NICKNAME_RESULT,
    REMOVE_TICKET_ID,
    TICKET_INVENTORY_STATE,
    TICKET_RECONCILE_RESULT,
    BRIDGE_READY
  }

  /**
   * 디코딩 결과입니다. fields는 방어적으로 복사하여 처리 중 외부 변경을 막습니다. field는 누락된 필드를 입력 오류로 변환하며 의미 검증은 플랫폼 처리기에서
   * 수행합니다.
   */
  public record Packet(Type type, UUID requestId, UUID playerId, List<String> fields) {
    public Packet {
      fields = List.copyOf(fields);
    }

    public String field(int i) {
      if (i < 0 || i >= fields.size()) throw new IllegalArgumentException("메시지 필드가 부족합니다.");
      return fields.get(i);
    }
  }

  /** 신뢰된 서버 코드가 보낼 packet을 제한된 크기의 바이너리 형식으로 작성합니다. */
  public static byte[] encode(Type type, UUID requestId, UUID playerId, String... fields) {
    try {
      ByteArrayOutputStream bytes = new ByteArrayOutputStream();
      try (DataOutputStream out = new DataOutputStream(bytes)) {
        out.writeInt(MAGIC);
        out.writeShort(VERSION);
        out.writeByte(type.ordinal());
        writeUuid(out, requestId);
        writeUuid(out, playerId);
        if (fields.length > MAX_FIELDS) throw new IllegalArgumentException("메시지 필드가 너무 많습니다.");
        out.writeByte(fields.length);
        for (String f : fields) writeString(out, f);
      }
      byte[] result = bytes.toByteArray();
      if (result.length > MAX_PACKET) throw new IllegalArgumentException("메시지가 너무 큽니다.");
      return result;
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }

  /** backend에서 받은 bytes를 완전히 소비했는지까지 확인해 뒤에 붙은 데이터를 거절합니다. */
  public static Packet decode(byte[] bytes) {
    if (bytes.length > MAX_PACKET) throw new IllegalArgumentException("메시지가 너무 큽니다.");
    try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes))) {
      if (in.readInt() != MAGIC || in.readShort() != VERSION)
        throw new IllegalArgumentException("지원하지 않는 메시지입니다.");
      int ord = in.readUnsignedByte();
      if (ord >= Type.values().length) throw new IllegalArgumentException("알 수 없는 메시지 유형입니다.");
      UUID request = readUuid(in), player = readUuid(in);
      int count = in.readUnsignedByte();
      if (count > MAX_FIELDS) throw new IllegalArgumentException("메시지 필드가 너무 많습니다.");
      List<String> fields = new ArrayList<>(count);
      for (int i = 0; i < count; i++) fields.add(readString(in));
      if (in.available() != 0) throw new IllegalArgumentException("알 수 없는 메시지 데이터입니다.");
      return new Packet(Type.values()[ord], request, player, fields);
    } catch (IOException e) {
      throw new IllegalArgumentException("손상된 메시지입니다.", e);
    }
  }

  /** UUID를 상위·하위 long 순서로 기록합니다. readUuid와 순서를 동일하게 유지해야 합니다. */
  private static void writeUuid(DataOutputStream out, UUID id) throws IOException {
    out.writeLong(id.getMostSignificantBits());
    out.writeLong(id.getLeastSignificantBits());
  }

  /** 고정 16바이트 UUID를 읽습니다. 부족한 바이트는 decode가 손상된 메시지로 변환합니다. */
  private static UUID readUuid(DataInputStream in) throws IOException {
    return new UUID(in.readLong(), in.readLong());
  }

  /** 문자 수 대신 UTF-8 바이트 수를 검사하고 unsigned short 길이 뒤에 payload를 기록합니다. */
  private static void writeString(DataOutputStream out, String value) throws IOException {
    byte[] b = value.getBytes(StandardCharsets.UTF_8);
    if (b.length > MAX_FIELD_BYTES) throw new IllegalArgumentException("메시지 문자열이 너무 깁니다.");
    out.writeShort(b.length);
    out.write(b);
  }

  /** 선언 길이가 필드 상한과 남은 packet 크기 이내인지 검사한 뒤 UTF-8로 해석합니다. */
  private static String readString(DataInputStream in) throws IOException {
    int len = in.readUnsignedShort();
    if (len > MAX_FIELD_BYTES || len > in.available())
      throw new IllegalArgumentException("잘못된 문자열 길이입니다.");
    return new String(in.readNBytes(len), StandardCharsets.UTF_8);
  }
}

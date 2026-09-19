package kr.seremc.nickname.velocity;

import java.io.*;
import java.nio.file.*;
import java.util.*;

/** UTF-8 properties 설정을 읽는 로더입니다. 기존 파일은 덮어쓰지 않으며 최초 실행에만 기본 파일을 복사합니다. */
final class VelocityConfig {
  private final Properties values = new Properties();

  private VelocityConfig() {}

  /** 데이터 폴더를 준비하고 설정을 로드합니다. 파일 접근 실패는 시작 단계로 IOException을 전파합니다. */
  static VelocityConfig load(Path directory) throws IOException {
    Files.createDirectories(directory);
    Path file = directory.resolve("config.properties");
    if (Files.notExists(file)) {
      try (InputStream in = VelocityConfig.class.getResourceAsStream("/config.properties")) {
        if (in == null) throw new IOException("기본 config.properties 없음");
        Files.copy(in, file);
      }
    }
    VelocityConfig config = new VelocityConfig();
    try (Reader reader = Files.newBufferedReader(file)) {
      config.values.load(reader);
    }
    return config;
  }

  /** 일반 설정의 앞뒤 공백을 제거합니다. 없는 키는 빈 문자열로 반환합니다. */
  String get(String key) {
    return values.getProperty(key, "").trim();
  }

  /** 정수가 없거나 파싱할 수 없으면 호출자가 지정한 기본값을 사용합니다. */
  int integer(String key, int fallback) {
    try {
      return Integer.parseInt(get(key));
    } catch (NumberFormatException e) {
      return fallback;
    }
  }

  /** 환경 변수가 존재하면 우선 사용합니다. 파일 값은 get을 사용하므로 앞뒤 공백이 제거됩니다. */
  String secret(String envKey, String fallbackKey) {
    String envName = get(envKey);
    String env = envName.isBlank() ? null : System.getenv(envName);
    return env == null ? get(fallbackKey) : env;
  }

  /** 쉼표로 나눈 금지어 등의 목록에서 공백과 빈 항목을 제거합니다. */
  List<String> list(String key) {
    return Arrays.stream(get(key).split(",")).map(String::trim).filter(v -> !v.isEmpty()).toList();
  }
}

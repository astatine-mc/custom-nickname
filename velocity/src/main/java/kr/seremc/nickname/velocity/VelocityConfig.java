package kr.seremc.nickname.velocity;

import java.io.*;
import java.nio.file.*;
import java.util.*;

final class VelocityConfig {
 private final Properties values=new Properties();
 private VelocityConfig() {}
 static VelocityConfig load(Path directory)throws IOException{
  Files.createDirectories(directory);Path file=directory.resolve("config.properties");
  if(Files.notExists(file)){try(InputStream in=VelocityConfig.class.getResourceAsStream("/config.properties")){if(in==null)throw new IOException("기본 config.properties 없음");Files.copy(in,file);}}
  VelocityConfig config=new VelocityConfig();try(Reader reader=Files.newBufferedReader(file)){config.values.load(reader);}return config;
 }
 String get(String key){return values.getProperty(key,"").trim();}
 int integer(String key,int fallback){try{return Integer.parseInt(get(key));}catch(NumberFormatException e){return fallback;}}
 String secret(String envKey,String fallbackKey){String envName=get(envKey);String env=envName.isBlank()?null:System.getenv(envName);return env==null?get(fallbackKey):env;}
 List<String> list(String key){return Arrays.stream(get(key).split(",")).map(String::trim).filter(v->!v.isEmpty()).toList();}
}

package kr.seremc.nickname.api;
import java.util.UUID;
public record NicknameProfile(UUID playerId, String accountName, String nickname, boolean custom, long revision) {}

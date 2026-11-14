package fu.coreservice.dto.chat;

import java.util.List;

public record ChatMessageWindow(List<ChatMessageResponse> messages, boolean hasOlder, Long oldestId) {}

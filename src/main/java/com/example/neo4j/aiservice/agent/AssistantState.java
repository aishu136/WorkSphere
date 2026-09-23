package com.example.neo4j.aiservice.agent;

import java.util.ArrayList;
import java.util.Map;

import org.bsc.langgraph4j.prebuilt.MessagesState;
import org.bsc.langgraph4j.state.Channel;
import org.bsc.langgraph4j.state.Channels;

import dev.langchain4j.data.message.ChatMessage;

/**
 * Graph state for the assistant: the conversation so far.
 *
 * The stock MessagesState schema drops a message that equals one already in the list, which
 * would silently lose a repeated model reply or tool result. This schema keeps every message.
 */
public class AssistantState extends MessagesState<ChatMessage> {

    public static final Map<String, Channel<?>> SCHEMA =
            Map.of(MESSAGES_STATE, Channels.appenderWithDuplicate(ArrayList::new));

    public AssistantState(Map<String, Object> initData) {
        super(initData);
    }
}

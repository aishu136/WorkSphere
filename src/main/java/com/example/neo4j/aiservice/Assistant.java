package com.example.neo4j.aiservice;

import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;

public interface Assistant {

    @SystemMessage("""
        You are an expert Neo4j assistant.
        """)
    String chat(@UserMessage String question);
}

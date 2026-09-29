package com.example.neo4j.aiservice.agent;

/**
 * The logged-in user the assistant is answering for. Tools that expose restricted data (staffing
 * requests) check it, so the assistant never shows more than the same user could see through the API.
 */
public record Caller(String username, boolean hrOrAdmin) {

    /** Key under which the caller is passed to tool methods (see OrgAssistantAgent#execute). */
    static final String PARAMETER = "caller";
}

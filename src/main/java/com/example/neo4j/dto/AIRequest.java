package com.example.neo4j.dto;


import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class AIRequest {

    // Optional hint: the assistant can also find people by itself.
    @Size(max = 100)
    private String employeeName;

    @NotBlank
    @Size(max = 1000)
    private String question;

}

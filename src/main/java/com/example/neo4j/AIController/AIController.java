package com.example.neo4j.AIController;

import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.neo4j.aiservice.AIService;
import com.example.neo4j.dto.AIRequest;

import jakarta.validation.Valid;

//@RestController
//@RequestMapping("/ai")
//public class AIController {
//
//    private final AIService service;
//
//    public AIController(AIService service) {
//        this.service = service;
//    }
//
//    @PostMapping
//    public String chat(@RequestBody String question) {
//        return service.ask(question);
//    }
//    
//    @PostMapping("/person/{name}")
//    public String personChat(@PathVariable("name") String name,
//                             @RequestBody String question) {
//
//        return service.askAboutPerson(name, question);
//    }
//}
@RestController
@RequestMapping("/ai")
public class AIController {

    private final AIService service;

    public AIController(AIService service) {
        this.service = service;
    }

    @PostMapping
    public String ask(@RequestBody @Valid AIRequest request) {

        return service.ask(request);

    }

}

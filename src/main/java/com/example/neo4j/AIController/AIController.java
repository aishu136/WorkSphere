package com.example.neo4j.AIController;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.neo4j.aiservice.AIService;
import com.example.neo4j.aiservice.agent.Caller;
import com.example.neo4j.dto.AIRequest;
import com.example.neo4j.dto.AIResponse;
import com.example.neo4j.security.Role;

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
    public AIResponse ask(@RequestBody @Valid AIRequest request, Authentication authentication) {

        boolean hrOrAdmin = authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .anyMatch(authority -> authority.equals("ROLE_" + Role.HR.name())
                        || authority.equals("ROLE_" + Role.ADMIN.name()));

        return service.ask(request, new Caller(authentication.getName(), hrOrAdmin));

    }

}

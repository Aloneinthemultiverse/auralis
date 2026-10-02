package ai.auralis.research.deep;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.json.JsonMapper;

import ai.auralis.config.AuralisProperties;

@Configuration
class DeepResearchConfig {

    @Bean
    LocalDeepResearch localDeepResearch(JsonMapper json, AuralisProperties props) {
        return new LocalDeepResearch(json, props);
    }

    @Bean
    KaggleDeepResearch kaggleDeepResearch(JsonMapper json, AuralisProperties props) {
        return new KaggleDeepResearch(json, props);
    }
}

package com.daniellaera.backend.it;

import com.daniellaera.backend.utils.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The login page reads /api/v3/features before the user has a token, so it must be public.
 * Regression: it used to fall under anyRequest().authenticated() and return 401, hiding the GitHub button.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
public class GithubFeatureControllerITTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void featuresIsPublic() throws Exception {
        mockMvc.perform(get("/api/v3/features"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.oauth2Enabled").isBoolean());
    }
}

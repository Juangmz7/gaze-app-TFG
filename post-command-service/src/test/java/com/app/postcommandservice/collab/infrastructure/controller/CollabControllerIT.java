package com.app.postcommandservice.collab.infrastructure.controller;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import com.app.postcommandservice.collab.application.commands.DeleteCollabCommand;
import com.app.postcommandservice.collab.application.usecase.DeleteCollabUseCase;
import com.app.postcommandservice.collab.application.usecase.OpenCollabAndCreatePostUseCase;
import com.app.postcommandservice.shared.infrastructure.security.SecurityUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CollabControllerIT {

    @Mock
    private OpenCollabAndCreatePostUseCase openCollabAndCreatePostUseCase;

    @Mock
    private DeleteCollabUseCase deleteCollabUseCase;

    @Mock
    private SecurityUtils securityUtils;

    @InjectMocks
    private CollabController collabController;

    @Test
    void shouldDeleteCollabAndReturnAccepted() {
        var collabId = UUID.randomUUID();
        var userId = UUID.randomUUID();
        when(securityUtils.getUserId()).thenReturn(userId);

        var response = collabController.deleteCollab(collabId);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        verify(deleteCollabUseCase).delete(new DeleteCollabCommand(collabId, userId));
    }
}

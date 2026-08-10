package com.app.socialservice.user.application.service;

import java.util.UUID;

import com.app.socialservice.user.application.commands.SynchroniseSecondaryDatabaseCommand;
import com.app.socialservice.user.infrastructure.entity.UserNode;
import com.app.socialservice.user.infrastructure.repository.UserNodeRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserNodeServiceTest {

    @Mock
    private UserNodeRepository userNodeRepository;

    @InjectMocks
    private UserNodeService userNodeService;

    @Test
    void shouldRegisterUserNode() {
        var command = new SynchroniseSecondaryDatabaseCommand(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID()
        );

        when(userNodeRepository.save(any(UserNode.class))).thenAnswer(invocation -> invocation.getArgument(0));

        userNodeService.registerUserNode(command);

        verify(userNodeRepository).save(any(UserNode.class));
    }

    @Test
    void shouldDeleteUserNode() {
        var command = new SynchroniseSecondaryDatabaseCommand(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID()
        );

        userNodeService.deleteUserNode(command);

        verify(userNodeRepository).deleteById(command.userId());
    }

}

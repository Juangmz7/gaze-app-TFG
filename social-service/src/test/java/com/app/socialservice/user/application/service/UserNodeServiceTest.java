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

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserNodeServiceTest {

    @Mock
    private UserNodeRepository userNodeRepository;

    @InjectMocks
    private UserNodeService userNodeService;

    @Test
    void shouldRegisterUserNodeWhenItDoesNotExist() {
        var command = new SynchroniseSecondaryDatabaseCommand(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID()
        );

        when(userNodeRepository.existsById(command.userId())).thenReturn(false);
        when(userNodeRepository.save(any(UserNode.class))).thenAnswer(invocation -> invocation.getArgument(0));

        userNodeService.registerUserNode(command);

        verify(userNodeRepository).save(any(UserNode.class));
    }

    @Test
    void shouldNotRegisterUserNodeWhenItAlreadyExists() {
        var command = new SynchroniseSecondaryDatabaseCommand(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID()
        );

        when(userNodeRepository.existsById(command.userId())).thenReturn(true);

        userNodeService.registerUserNode(command);

        verify(userNodeRepository, never()).save(any(UserNode.class));
    }

    @Test
    void shouldDeleteUserNodeWhenItExists() {
        var command = new SynchroniseSecondaryDatabaseCommand(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID()
        );

        when(userNodeRepository.existsById(command.userId())).thenReturn(true);

        userNodeService.deleteUserNode(command);

        verify(userNodeRepository).deleteById(command.userId());
    }

    @Test
    void shouldIgnoreDeleteWhenUserNodeDoesNotExist() {
        var command = new SynchroniseSecondaryDatabaseCommand(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID()
        );

        when(userNodeRepository.existsById(command.userId())).thenReturn(false);

        userNodeService.deleteUserNode(command);

        verify(userNodeRepository, never()).deleteById(any());
    }

    @Test
    void shouldThrowWhenSynchroniseCommandUserIdIsNull() {
        var command = new SynchroniseSecondaryDatabaseCommand(
                UUID.randomUUID(),
                UUID.randomUUID(),
                null
        );

        assertThatThrownBy(() -> userNodeService.registerUserNode(command))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("command.userId must not be null");
    }
}

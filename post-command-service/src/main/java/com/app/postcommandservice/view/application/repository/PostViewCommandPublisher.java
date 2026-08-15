package com.app.postcommandservice.view.application.repository;

import com.app.postcommandservice.view.application.commands.ProcessPostViewCommand;

public interface PostViewCommandPublisher {

    void publish(ProcessPostViewCommand command);
}

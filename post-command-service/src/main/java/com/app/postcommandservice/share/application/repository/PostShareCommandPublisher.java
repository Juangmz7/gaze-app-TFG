package com.app.postcommandservice.share.application.repository;

import com.app.postcommandservice.share.application.commands.CreatePostShareCommand;

public interface PostShareCommandPublisher {

    void publish(CreatePostShareCommand command);
}

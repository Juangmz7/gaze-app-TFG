package com.app.postcommandservice.like.application.repository;

import com.app.postcommandservice.like.application.commands.ValidatePostLikeCommand;

public interface PostLikeCommandPublisher {

    void publish(ValidatePostLikeCommand command);
}

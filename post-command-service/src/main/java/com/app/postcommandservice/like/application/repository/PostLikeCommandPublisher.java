package com.app.postcommandservice.like.application.repository;

import com.app.postcommandservice.like.application.commands.ValidatePostLikeCommand;
import com.app.postcommandservice.like.application.commands.ValidatePostUnlikeCommand;

public interface PostLikeCommandPublisher {

    void publish(ValidatePostLikeCommand command);

    void publish(ValidatePostUnlikeCommand command);
}

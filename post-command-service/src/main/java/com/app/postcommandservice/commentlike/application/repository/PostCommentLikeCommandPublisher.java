package com.app.postcommandservice.commentlike.application.repository;

import com.app.postcommandservice.commentlike.application.commands.ValidateCommentLikeCommand;

public interface PostCommentLikeCommandPublisher {

    void publish(ValidateCommentLikeCommand command);
}

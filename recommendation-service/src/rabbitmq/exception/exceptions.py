class RejectAndDontRequeueError(Exception):
    """
    Marks an event as permanently non-processable.

    The message must be rejected immediately without retry.
    """
    pass
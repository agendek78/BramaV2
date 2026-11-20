package com.agendek.bramav2.protocol

/** Naruszenie formatu lub reguł protokołu v2; ramki nie wolno interpretować. */
class ProtocolException(message: String) : Exception(message)

/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package kafka.network

import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.nio.channels._

import kafka.utils.{CoreUtils, Logging, nonthreadsafe}
import kafka.api.RequestOrResponse
import kafka.common.security.LoginManager
import kafka.utils.{CoreUtils, Logging, nonthreadsafe}
import org.apache.kafka.common.config.SaslConfigs
import org.apache.kafka.common.memory.MemoryPool
import org.apache.kafka.common.network._
import org.apache.kafka.common.security.auth.SecurityProtocol
import org.apache.kafka.common.security.authenticator.SaslClientAuthenticator
import org.apache.kafka.common.utils.Time


@deprecated("This object has been deprecated and will be removed in a future release.", "0.11.0.0")
object BlockingChannel{
  val UseDefaultBufferSize = -1
}

/**
 *  A simple blocking channel with timeouts correctly enabled.
 *
 */
@nonthreadsafe
class BlockingChannel( val host: String,
                       val port: Int,
                       val readBufferSize: Int,
                       val writeBufferSize: Int,
                       val readTimeoutMs: Int,
                       val protocol: SecurityProtocol = SecurityProtocol.PLAINTEXT) extends Logging {

  private var connected = false
  private var channel: KafkaChannel = null
  private var readChannel: ReadableByteChannel = null
  private var writeChannel: GatheringByteChannel = null
  private val id = "-2"
  private val lock = new Object()
  private val connectTimeoutMs = readTimeoutMs
  private val handshakeTimeoutMs = readTimeoutMs


  def connect() = lock synchronized  {
    if(!connected) {
      try {
        val socketChannel = SocketChannel.open()
        if(readBufferSize > 0)
          socketChannel.socket.setReceiveBufferSize(readBufferSize)
        if(writeBufferSize > 0)
          socketChannel.socket.setSendBufferSize(writeBufferSize)
        socketChannel.configureBlocking(true)
        socketChannel.socket.setSoTimeout(readTimeoutMs)
        socketChannel.socket.setKeepAlive(true)
        socketChannel.socket.setTcpNoDelay(true)
        socketChannel.socket.connect(new InetSocketAddress(host, port), connectTimeoutMs)
        channel = buildKafkaChannel(socketChannel, readBufferSize, id)

        val handshakeInterval = Time.SYSTEM.milliseconds
        while(!channel.ready) {
          channel.prepare();
          if (!channel.ready && ((Time.SYSTEM.milliseconds - handshakeInterval) > handshakeTimeoutMs)) {
            throw new SocketTimeoutException("Socket timeout during handshake")
          }
        }

        writeChannel = channel.transportLayer
        readChannel = Channels.newChannel(channel.transportLayer.socketChannel().socket().getInputStream)
        connected = true
        // settings may not match what we requested above
        val msg = "Created socket with SO_TIMEOUT = %d (requested %d), SO_RCVBUF = %d (requested %d), SO_SNDBUF = %d (requested %d), connectTimeoutMs = %d."
        debug(msg.format(channel.transportLayer.socketChannel.socket.getSoTimeout,
                         readTimeoutMs,
                         channel.transportLayer.socketChannel.socket.getReceiveBufferSize,
                         readBufferSize,
                         channel.transportLayer.socketChannel.socket.getSendBufferSize,
                         writeBufferSize,
                         connectTimeoutMs))

      } catch {
        case _: Throwable => disconnect()
      }
    }
  }

  def disconnect() = lock synchronized {
    if(channel != null) {
      CoreUtils.swallow(channel.close(), this)
      channel = null
      writeChannel = null
    }
    // closing the main socket channel *should* close the read channel
    // but let's do it to be sure.
    if(readChannel != null) {
      CoreUtils.swallow(readChannel.close(), this)
      readChannel = null
    }
    connected = false
  }

  def isConnected = connected

  def send(request: RequestOrResponse):Long = {
    if(!connected)
      throw new ClosedChannelException()
    val send = new RequestOrResponseSend(id, request)
    send.writeCompletely(writeChannel)
  }

  def receive(): NetworkReceive = {
    if(!connected)
      throw new ClosedChannelException()

    val response = readCompletely(readChannel)
    response.payload().rewind()

    response
  }

  private def readCompletely(channel: ReadableByteChannel): NetworkReceive = {
    val response = new NetworkReceive
    while (!response.complete())
      response.readFromReadableChannel(channel)
    response
  }

  private def buildKafkaChannel(socketChannel: SocketChannel, maxReceiveSize: Int, id: String): KafkaChannel = {
    val transportLayer = new BlockingPlaintextTransportLayer(socketChannel)
    var authenticator : Authenticator = null

    if (CoreUtils.isSaslProtocol(protocol))
      authenticator = new SaslClientAuthenticator(new java.util.HashMap[String, Any](), id, LoginManager.subject, LoginManager.serviceName,
        socketChannel.socket().getInetAddress().getHostName(), SaslConfigs.DEFAULT_SASL_MECHANISM, true, transportLayer)
    else
      authenticator = new BlockingPlaintextAuthenticator(new java.util.HashMap[String, Any](), transportLayer);

    return new KafkaChannel(id, transportLayer, authenticator, maxReceiveSize, MemoryPool.NONE)
  }
}

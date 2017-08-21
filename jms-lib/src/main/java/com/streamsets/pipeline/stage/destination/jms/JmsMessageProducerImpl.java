/*
 * Copyright 2017 StreamSets Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.streamsets.pipeline.stage.destination.jms;

import com.streamsets.pipeline.api.Batch;
import com.streamsets.pipeline.api.Record;
import com.streamsets.pipeline.api.Stage;
import com.streamsets.pipeline.api.StageException;
import com.streamsets.pipeline.api.Target;
import com.streamsets.pipeline.api.impl.Utils;
import com.streamsets.pipeline.config.DataFormat;
import com.streamsets.pipeline.lib.generator.DataGenerator;
import com.streamsets.pipeline.lib.generator.DataGeneratorFactory;
import com.streamsets.pipeline.lib.jms.config.JmsErrors;
import com.streamsets.pipeline.lib.jms.config.JmsGroups;
import com.streamsets.pipeline.stage.common.CredentialsConfig;
import com.streamsets.pipeline.stage.common.DataFormatErrors;
import com.streamsets.pipeline.stage.destination.lib.DataGeneratorFormatConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.jms.BytesMessage;
import javax.jms.Connection;
import javax.jms.ConnectionFactory;
import javax.jms.Destination;
import javax.jms.JMSException;
import javax.jms.Message;
import javax.jms.MessageProducer;
import javax.jms.Session;
import javax.naming.InitialContext;
import javax.naming.NamingException;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

public class JmsMessageProducerImpl implements JmsMessageProducer {
  private static final Logger LOG = LoggerFactory.getLogger(JmsMessageProducerImpl.class);
  private static final String CONN_FACTORY_CONFIG_NAME = "jmsTargetConfig.connectionFactory";
  private static final String DEST_NAME_CONFIG_NAME = "jmsTargetConfig.destinationName";
  private final InitialContext initialContext;
  private final ConnectionFactory connectionFactory;
  private final DataFormat dataFormat;
  private final DataGeneratorFormatConfig dataFormatConfig;
  private final CredentialsConfig credentialsConfig;
  private final JmsTargetConfig jmsTargetConfig;
  private Connection connection;
  private Session session;
  private Destination destination;
  private MessageProducer messageProducer;

  public JmsMessageProducerImpl(
    InitialContext initialContext,
    ConnectionFactory connectionFactory,
    DataFormat dataFormat,
    DataGeneratorFormatConfig dataFormatConfig,
    CredentialsConfig credentialsConfig,
    JmsTargetConfig jmsTargetConfig)
  {
    this.initialContext = initialContext;
    this.connectionFactory = connectionFactory;
    this.dataFormat = dataFormat;
    this.dataFormatConfig = dataFormatConfig;
    this.credentialsConfig = credentialsConfig;
    this.jmsTargetConfig = jmsTargetConfig;
  }

  @Override
  public List<Stage.ConfigIssue> init(Target.Context context) {
    List<Stage.ConfigIssue> issues = new ArrayList<>();
    try {
      if(credentialsConfig.useCredentials) {
        connection = connectionFactory.createConnection(credentialsConfig.username, credentialsConfig.password);
      } else {
        connection = connectionFactory.createConnection();
      }
    } catch (JMSException ex) {
      if (credentialsConfig.useCredentials) {
        issues.add(context.createConfigIssue(
            JmsGroups.JMS.name(), CONN_FACTORY_CONFIG_NAME, JmsErrors.JMS_03,
            connectionFactory.getClass().getName(), credentialsConfig.username, ex.toString()));
        LOG.info(Utils.format(JmsErrors.JMS_03.getMessage(), connectionFactory.getClass().getName(),
            credentialsConfig.username, ex.toString()), ex);
      } else {
        issues.add(context.createConfigIssue(JmsGroups.JMS.name(), CONN_FACTORY_CONFIG_NAME, JmsErrors.JMS_02,
            connectionFactory.getClass().getName(), ex.toString()));
        LOG.info(Utils.format(JmsErrors.JMS_02.getMessage(), connectionFactory.getClass().getName(), ex.toString())
            , ex);
      }
    }
    if(issues.isEmpty()) {
      try {
        connection.start();
      } catch (JMSException ex) {
        issues.add(context.createConfigIssue(JmsGroups.JMS.name(), CONN_FACTORY_CONFIG_NAME, JmsErrors.JMS_04,
            ex.toString()));
        LOG.info(Utils.format(JmsErrors.JMS_04.getMessage(), ex.toString()), ex);
      }
    }
    if(issues.isEmpty()) {
      try {
        session = connection.createSession(true, Session.SESSION_TRANSACTED);
      } catch (JMSException ex) {
        issues.add(context.createConfigIssue(JmsGroups.JMS.name(), CONN_FACTORY_CONFIG_NAME, JmsErrors.JMS_06,
            ex.toString()));
        LOG.info(Utils.format(JmsErrors.JMS_06.getMessage(), ex.toString()), ex);
      }
    }
    if(issues.isEmpty()) {
      try {
        switch (jmsTargetConfig.destinationType) {
          case UNKNOWN:
            destination = (Destination) initialContext.lookup(jmsTargetConfig.destinationName);
            break;
          case QUEUE:
            destination = session.createQueue(jmsTargetConfig.destinationName);
            break;
          case TOPIC:
            destination = session.createTopic(jmsTargetConfig.destinationName);
            break;
          default:
            throw new IllegalArgumentException(Utils.format("Unknown destination type: {}", jmsTargetConfig.destinationName));
        }
      } catch (JMSException | NamingException ex) {
        issues.add(context.createConfigIssue(JmsGroups.JMS.name(), DEST_NAME_CONFIG_NAME, JmsErrors.JMS_05,
            jmsTargetConfig.destinationName, String.valueOf(ex)));
        LOG.info(Utils.format(JmsErrors.JMS_05.getMessage(), jmsTargetConfig.destinationName,
            String.valueOf(ex)), ex);
      }
    }
    if(issues.isEmpty()) {
      try {
        messageProducer = session.createProducer(destination);
      } catch (JMSException ex) {
        issues.add(context.createConfigIssue(JmsGroups.JMS.name(), CONN_FACTORY_CONFIG_NAME, JmsErrors.JMS_11,
            ex.toString()));
        LOG.info(Utils.format(JmsErrors.JMS_11.getMessage(), ex.toString()), ex);
      }
    }

    return issues;
  }

  @Override
  public int put(Batch batch, DataGeneratorFactory generatorFactory) throws StageException {
    Iterator<Record> records = batch.getRecords();
    ByteArrayOutputStream baos = new ByteArrayOutputStream();

    int count = 0;
    while (records.hasNext()) {
      baos.reset();
      try (DataGenerator generator = generatorFactory.getGenerator(baos)) {
        generator.write(records.next());
      } catch (IOException e) {
        LOG.error("Failed to write Records: {}", e);
        throw new StageException(JmsErrors.JMS_12, e.getMessage(), e);
      }

      handleDelivery(baos.toByteArray());
      count++;
    }

    return count;
  }

  private void handleDelivery(byte[] payload) throws StageException {
    Message message;
    try {
      switch (this.dataFormat) {
        case DELIMITED:
        case DATAGRAM:
        case JSON:
        case SDC_JSON:
        case TEXT:
        case XML:
          message = session.createTextMessage(new String(payload, this.dataFormatConfig.charset));
          break;
        case PROTOBUF:
        case AVRO:
        case BINARY:
          BytesMessage bytesMessage = session.createBytesMessage();
          bytesMessage.writeBytes(payload);
          message = bytesMessage;
          break;
        default:
          LOG.error("Unsupported data format type: {}", this.dataFormat);
          throw new StageException(JmsErrors.JMS_10, this.dataFormat);
      }

      messageProducer.send(message);
    } catch (JMSException e) {
      LOG.error("Could not produce message: {}", e);
      throw new StageException(JmsErrors.JMS_13, e.getMessage(), e);
    } catch (UnsupportedEncodingException e) {
      LOG.error("Unsupported charset: {}", this.dataFormatConfig.charset);
      throw new StageException(DataFormatErrors.DATA_FORMAT_05, this.dataFormatConfig.charset, e);
    }
  }

  @Override
  public void commit() throws StageException {
    try {
      session.commit();
    } catch (JMSException ex) {
      throw new StageException(JmsErrors.JMS_08, ex.toString(), ex);
    }
  }

  @Override
  public void rollback() throws StageException {
    try {
      session.rollback();
    } catch (JMSException ex) {
      throw new StageException(JmsErrors.JMS_09, ex.toString(), ex);
    }
  }

  @Override
  public void close() {
    if (session != null) {
      try {
        session.close();
      } catch (JMSException ex) {
        LOG.warn("Error closing session: " + ex, ex);
      }
    }
    if (connection != null) {
      try {
        connection.close();
      } catch (JMSException ex) {
        LOG.warn("Error closing connection: " + ex, ex);
      }
    }
  }
}

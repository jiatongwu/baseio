/*
 * Copyright 2015 The Baseio Project
 *  
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *  
 *      http://www.apache.org/licenses/LICENSE-2.0
 *  
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.firenio.baseio.component;

import com.firenio.baseio.common.Util;
import com.firenio.baseio.log.Logger;
import com.firenio.baseio.log.LoggerFactory;

public class ChannelActiveListener implements ChannelIdleListener {

    private Logger logger = LoggerFactory.getLogger(getClass());

    @Override
    public void channelIdled(Channel ch, long lastIdleTime, long currentTime) {
        if (ch.isOpen()) {
            if (ch.getLastAccessTime() < lastIdleTime) {
                logger.info("Did not detect hb in hb cycle, prepare to disconnect {}", ch);
                Util.close(ch);
            } else {
                ProtocolCodec codec = ch.getCodec();
                Frame frame = codec.ping(ch);
                if (frame == null) {
                    // 该channel无需心跳,比如HTTP协议
                    return;
                }
                try {
                    ch.writeAndFlush(frame);
                } catch (Exception e) {
                    logger.error(e.getMessage(), e);
                }
            }
        }
    }

}

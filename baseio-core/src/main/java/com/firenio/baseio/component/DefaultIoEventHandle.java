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

import com.firenio.baseio.log.Logger;
import com.firenio.baseio.log.LoggerFactory;

/**
 * @author wangkai
 *
 */
public class DefaultIoEventHandle extends IoEventHandle {

    private static final DefaultIoEventHandle handle = new DefaultIoEventHandle();

    private Logger                            logger = LoggerFactory.getLogger(getClass());

    private DefaultIoEventHandle() {}

    @Override
    public void accept(Channel ch, Frame frame) throws Exception {
        logger.info("you did not set IoEventHandleAdaptor and the msg is coming:{}", frame);
    }

    public static DefaultIoEventHandle get() {
        return handle;
    }

}

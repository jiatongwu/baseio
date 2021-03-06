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
package test.others;

/**
 * @author wangkai
 *
 */
public class TestGcd {

    // before
    public static int gcd(int a, int b) {
        int tmp;
        if (a > b) {
            tmp = a;
            a = b;
            b = tmp;
        }
        int i = a;
        for (;;) {
            if (i == 1) {
                return 1;
            }
            if (a % i == 0 && b % i == 0) {
                return i;
            }
            i--;
        }
    }

    //after
    public static int gcd1(int a, int b) {
        return b == 0 ? a : gcd1(b, a % b);
    }

}

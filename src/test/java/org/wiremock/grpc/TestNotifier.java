/*
 * Copyright (C) 2025 Thomas Akehurst
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.wiremock.grpc;

import com.github.tomakehurst.wiremock.common.Notifier;
import java.util.ArrayList;
import java.util.List;

public class TestNotifier implements Notifier {

  public List<String> infoMessages = new ArrayList<>();
  public List<String> errorMessages = new ArrayList<>();
  public List<Throwable> throwables = new ArrayList<>();

  public int numberOfMessages = 0;

  public void clear() {
    infoMessages.clear();
    errorMessages.clear();
    throwables.clear();
    numberOfMessages = 0;
  }

  @Override
  public void info(String message) {
    infoMessages.add(message);
    System.out.println(++numberOfMessages + "\t" + message);
  }

  @Override
  public void error(String message) {
    errorMessages.add(message);
    System.err.println(++numberOfMessages + "\t" + message);
  }

  @Override
  public void error(String message, Throwable t) {
    errorMessages.add(message);
    throwables.add(t);
    System.err.println(++numberOfMessages + "\t" + message);
  }
}

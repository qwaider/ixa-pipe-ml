/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package eus.ixa.ixa.pipe.ml.parse;

import java.util.List;

import opennlp.tools.ml.model.Event;
import opennlp.tools.parser.ParserEventTypeEnum;
import opennlp.tools.util.ObjectStream;
import eus.ixa.ixa.pipe.ml.sequence.SequenceLabelerContextGenerator;

/**
 * Wrapper class for one of four parser event streams. The particular event
 * stream is specified at construction.
 */
public class ParserEventStream extends AbstractParserEventStream {

  protected BuildContextGenerator bcg;
  protected CheckContextGenerator kcg;

  /**
   * Create an event stream based on the specified data stream of the specified
   * type using the specified head rules.
   * 
   * @param d
   *          A 1-parse-per-line Penn Treebank Style parse.
   * @param rules
   *          The head rules.
   * @param etype
   *          The type of events desired (tag, chunk, build, or check).
   * @param dict
   *          A tri-gram dictionary to reduce feature generation.
   */
  public ParserEventStream(ObjectStream<Parse> samples, HeadRules rules,
      ParserEventTypeEnum etype,
      SequenceLabelerContextGenerator taggerContextGenerator,
      SequenceLabelerContextGenerator chunkerContextGenerator,
      ParserFactory factory) {
    super(samples, rules, etype, taggerContextGenerator,
        chunkerContextGenerator, factory);
  }

  @Override
  protected void init() {
    if (etype == ParserEventTypeEnum.BUILD) {
      this.bcg = new BuildContextGenerator(factory.getDictionary(),
          factory.getResources());
    } else if (etype == ParserEventTypeEnum.CHECK) {
      this.kcg = new CheckContextGenerator(factory.getResources());
    }
  }

  public ParserEventStream(ObjectStream<Parse> d, HeadRules rules,
      ParserEventTypeEnum etype,
      SequenceLabelerContextGenerator taggerContextGenerator,
      SequenceLabelerContextGenerator chunkerContextGenerator) {
    this(d, rules, etype, taggerContextGenerator, chunkerContextGenerator, null);
  }

  /**
   * Returns true if the specified child is the first child of the specified
   * parent.
   * 
   * @param child
   *          The child parse.
   * @param parent
   *          The parent parse.
   * @return true if the specified child is the first child of the specified
   *         parent; false otherwise.
   */
  protected boolean firstChild(Parse child, Parse parent) {
    return ShiftReduceParser
        .collapsePunctuation(parent.getChildren(), punctSet)[0] == child;
  }

  public static Parse[] reduceChunks(Parse[] chunks, int ci, Parse parent) {
    String type = parent.getType();
    // perform reduce
    int reduceStart = ci;
    int reduceEnd = ci;
    while (reduceStart >= 0 && chunks[reduceStart].getParent() == parent) {
      reduceStart--;
    }
    reduceStart++;
    Parse[] reducedChunks;
    if (!type.equals(ShiftReduceParser.TOP_NODE)) {
      reducedChunks = new Parse[chunks.length - (reduceEnd - reduceStart + 1)
          + 1]; // total - num_removed + 1 (for new node)
      // insert nodes before reduction
      for (int ri = 0, rn = reduceStart; ri < rn; ri++) {
        reducedChunks[ri] = chunks[ri];
      }
      // insert reduced node
      reducedChunks[reduceStart] = parent;
      // propagate punctuation sets
      parent
          .setPrevPunctuation(chunks[reduceStart].getPreviousPunctuationSet());
      parent.setNextPunctuation(chunks[reduceEnd].getNextPunctuationSet());
      // insert nodes after reduction
      int ri = reduceStart + 1;
      for (int rci = reduceEnd + 1; rci < chunks.length; rci++) {
        reducedChunks[ri] = chunks[rci];
        ri++;
      }
      ci = reduceStart - 1; // ci will be incremented at end of loop
    } else {
      reducedChunks = new Parse[0];
    }
    return reducedChunks;
  }

  /**
   * Adds events for parsing (post tagging and chunking to the specified list of
   * events for the specified parse chunks.
   * 
   * @param parseEvents
   *          The events for the specified chunks.
   * @param chunks
   *          The incomplete parses to be parsed.
   */
  @Override
  protected void addParseEvents(List<Event> parseEvents, Parse[] chunks) {
    int ci = 0;
    while (ci < chunks.length) {
      // System.err.println("parserEventStream.addParseEvents: chunks="+Arrays.asList(chunks));
      Parse c = chunks[ci];
      Parse parent = c.getParent();
      if (parent != null) {
        String type = parent.getType();
        String outcome;
        if (firstChild(c, parent)) {
          outcome = ShiftReduceParser.START + type;
        } else {
          outcome = ShiftReduceParser.CONT + type;
        }
        // System.err.println("parserEventStream.addParseEvents: chunks["+ci+"]="+c+" label="+outcome+" bcg="+bcg);
        c.setLabel(outcome);
        if (etype == ParserEventTypeEnum.BUILD) {
          parseEvents.add(new Event(outcome, bcg.getContext(chunks, ci)));
        }
        int start = ci - 1;
        while (start >= 0 && chunks[start].getParent() == parent) {
          start--;
        }
        if (lastChild(c, parent)) {
          if (etype == ParserEventTypeEnum.CHECK) {
            parseEvents.add(new Event(ShiftReduceParser.COMPLETE, kcg
                .getContext(chunks, type, start + 1, ci)));
          }
          // perform reduce
          int reduceStart = ci;
          while (reduceStart >= 0 && chunks[reduceStart].getParent() == parent) {
            reduceStart--;
          }
          reduceStart++;
          chunks = reduceChunks(chunks, ci, parent);
          ci = reduceStart - 1; // ci will be incremented at end of loop
        } else {
          if (etype == ParserEventTypeEnum.CHECK) {
            parseEvents.add(new Event(ShiftReduceParser.INCOMPLETE, kcg
                .getContext(chunks, type, start + 1, ci)));
          }
        }
      }
      ci++;
    }
  }

}

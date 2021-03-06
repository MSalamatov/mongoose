package com.emc.mongoose.base.item.io;

import com.emc.mongoose.base.env.FsUtil;
import com.emc.mongoose.base.item.Item;
import com.emc.mongoose.base.item.op.Operation;
import com.github.akurilov.commons.io.Input;
import com.github.akurilov.commons.io.Output;
import com.github.akurilov.commons.io.file.TextFileOutput;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Created by kurila on 09.01.17. */
public final class ItemInfoFileOutput<I extends Item, O extends Operation> implements Output<O> {

  private final Output<String> itemInfoOutput;

  public ItemInfoFileOutput(final Path filePath) throws IOException {
    FsUtil.createParentDirsIfNotExist(filePath);
    itemInfoOutput = new TextFileOutput(filePath);
  }

  @Override
  public final boolean put(final O ioResult) throws IOException {
    if (ioResult == null) { // poison
      close();
      return true;
    }
    return itemInfoOutput.put(ioResult.item().toString());
  }

  @Override
  public final int put(final List<O> ioResults, final int from, final int to) throws IOException {
    final int n = to - from;
    final List<String> itemsInfo = new ArrayList<>(n);
    O ioResult;
    for (int i = from; i < to; i++) {
      ioResult = ioResults.get(i);
      if (ioResult == null) { // poison
        try {
          return itemInfoOutput.put(itemsInfo, 0, i);
        } finally {
          close();
        }
      }
      itemsInfo.add(ioResult.item().toString());
    }
    return itemInfoOutput.put(itemsInfo, 0, n);
  }

  @Override
  public final int put(final List<O> ioResults) throws IOException {
    final List<String> itemsInfo = new ArrayList<>(ioResults.size());
    for (final O nextIoResult : ioResults) {
      if (nextIoResult == null) { // poison
        try {
          return itemInfoOutput.put(itemsInfo);
        } finally {
          close();
        }
      }
      itemsInfo.add(nextIoResult.item().toString());
    }
    return itemInfoOutput.put(itemsInfo);
  }

  @Override
  public final Input<O> getInput() throws IOException {
    throw new AssertionError();
  }

  @Override
  public final void close() throws IOException {
    itemInfoOutput.close();
  }
}

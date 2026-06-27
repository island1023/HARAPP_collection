activity为开发的人类识别活动APP，进行实时识别
HARDataCollector为自采数据，与UCI-HAR数据集的数据大致相同，由于保存目录为系统私有目录，无法导出
wisdmcollector为自采数据，与WISDM数据集的数据大致相同，但频率为50赫兹，导出的csv文件以及进行了训练，得到了对应的test.tflite

由于自采数据训练后的结果出现算子不匹配，无法使用，因此实际APP只有腰部和裤兜两种放置方式进行识别
算子不匹配的原因可能是输入维度无法匹配，test.tflite的输入维度为？×6×128，而har.tflite 输入维度为1×9×128，wisdm.tflite 输入维度为1×3×28

腰部模式，手机竖向正放，屏幕朝外(远离身体)
裤兜模式，手机竖向倒插，屏幕朝外(远离身体)

我的电脑没有单独的GPU，数据训练在Google Colab中进行，采用T4(GPU) python3，相关ipynb文件已经导出

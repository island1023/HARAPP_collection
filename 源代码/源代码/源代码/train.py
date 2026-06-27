import torch
import torch.nn as nn
import torch.optim as optim
from torch.utils.data import DataLoader, TensorDataset
import numpy as np
import os

# ==========================================
# 1. 数据加载工具函数
# ==========================================
def load_signals(data_path, subset):
    """
    读取 Inertial Signals 目录下的 9 轴惯性信号数据
    """
    signals_path = os.path.join(data_path, subset, 'Inertial Signals')
    files = [
        'body_acc_x_', 'body_acc_y_', 'body_acc_z_',
        'body_gyro_x_', 'body_gyro_y_', 'body_gyro_z_',
        'total_acc_x_', 'total_acc_y_', 'total_acc_z_'
    ]
    
    data = []
    for f in files:
        file_path = os.path.join(signals_path, f + subset + '.txt')
        if not os.path.exists(file_path):
            raise FileNotFoundError(f"找不到文件: {file_path}")
        # 使用 np.loadtxt 读取数据
        data.append(np.loadtxt(file_path))
    
    # 形状转换: (9, samples, 128) -> (samples, 128, 9)
    return np.transpose(np.array(data), (1, 2, 0))

def load_y(data_path, subset):
    """
    读取标签文件
    """
    file_path = os.path.join(data_path, subset, 'y_' + subset + '.txt')
    if not os.path.exists(file_path):
        raise FileNotFoundError(f"找不到标签文件: {file_path}")
    y = np.loadtxt(file_path)
    return y - 1  # 1-6 转为 0-5

# ==========================================
# 2. CNN-Transformer 模型定义
# ==========================================
class CNNTransformer(nn.Module):
    def __init__(self, num_classes=6):
        super(CNNTransformer, self).__init__()
        
        # CNN 提取局部特征
        self.conv_layer = nn.Sequential(
            nn.Conv1d(in_channels=9, out_channels=64, kernel_size=5, stride=1, padding=2),
            nn.BatchNorm1d(64),
            nn.ReLU(),
            nn.MaxPool1d(kernel_size=2)
        )
        
        # Transformer 提取长距离依赖
        encoder_layer = nn.TransformerEncoderLayer(
            d_model=64, 
            nhead=8, 
            dim_feedforward=128, 
            dropout=0.1, 
            batch_first=True
        )
        self.transformer_encoder = nn.TransformerEncoder(encoder_layer, num_layers=2)
        
        # 分类头
        self.classifier = nn.Sequential(
            nn.Linear(64, 32),
            nn.ReLU(),
            nn.Dropout(0.5),
            nn.Linear(32, num_classes)
        )

    def forward(self, x):
        # x: (Batch, 128, 9)
        x = x.transpose(1, 2)    # (Batch, 9, 128)
        x = self.conv_layer(x)   # (Batch, 64, 64)
        x = x.transpose(1, 2)    # (Batch, 64, 64)
        x = self.transformer_encoder(x)
        x = torch.mean(x, dim=1) # 全局平均池化
        return self.classifier(x)

# ==========================================
# 3. 导出 ONNX 函数
# ==========================================
def export_to_onnx(model_path, device):
    print("\n--- 正在导出 ONNX 模型 ---")
    model = CNNTransformer(num_classes=6)
    model.load_state_dict(torch.load(model_path, map_location=device))
    model.to(device)
    model.eval()

    dummy_input = torch.randn(1, 128, 9).to(device)
    torch.onnx.export(
        model, 
        dummy_input, 
        "har_transformer.onnx", 
        export_params=True, 
        opset_version=12, 
        do_constant_folding=True, 
        input_names=['input'], 
        output_names=['output'],
        dynamic_axes={'input': {0: 'batch_size'}, 'output': {0: 'batch_size'}}
    )
    print("导出成功: har_transformer.onnx")

# ==========================================
# 4. 主训练流程
# ==========================================
def main():
    device = torch.device("cuda" if torch.cuda.is_available() else "cpu")
    print(f"使用设备: {device}")

    # 直接使用你当前目录下的精简路径
    data_root = './UCI_HAR_Dataset' 
    
    try:
        print("正在加载数据...")
        x_train_np = load_signals(data_root, 'train')
        y_train_np = load_y(data_root, 'train')
        x_test_np = load_signals(data_root, 'test')
        y_test_np = load_y(data_root, 'test')
        
        x_train = torch.tensor(x_train_np, dtype=torch.float32)
        y_train = torch.tensor(y_train_np, dtype=torch.long)
        x_test = torch.tensor(x_test_np, dtype=torch.float32)
        y_test = torch.tensor(y_test_np, dtype=torch.long)
        print(f"加载成功! 训练集样本数: {len(x_train)}")
    except Exception as e:
        print(f"数据加载失败: {e}")
        return

    train_loader = DataLoader(TensorDataset(x_train, y_train), batch_size=64, shuffle=True)
    test_loader = DataLoader(TensorDataset(x_test, y_test), batch_size=64, shuffle=False)

    model = CNNTransformer().to(device)
    criterion = nn.CrossEntropyLoss()
    optimizer = optim.Adam(model.parameters(), lr=0.001)

    best_acc = 0.0
    epochs = 20

    print("开始训练...")
    for epoch in range(epochs):
        model.train()
        train_loss = 0.0
        for batch_x, batch_y in train_loader:
            batch_x, batch_y = batch_x.to(device), batch_y.to(device)
            optimizer.zero_grad()
            outputs = model(batch_x)
            loss = criterion(outputs, batch_y)
            loss.backward()
            optimizer.step()
            train_loss += loss.item()

        model.eval()
        correct, total = 0, 0
        with torch.no_grad():
            for batch_x, batch_y in test_loader:
                batch_x, batch_y = batch_x.to(device), batch_y.to(device)
                outputs = model(batch_x)
                _, predicted = torch.max(outputs.data, 1)
                total += batch_y.size(0)
                correct += (predicted == batch_y).sum().item()
        
        acc = correct / total
        print(f"Epoch [{epoch+1}/{epochs}] Loss: {train_loss/len(train_loader):.4f} Acc: {acc:.4f}")

        if acc > best_acc:
            best_acc = acc
            torch.save(model.state_dict(), 'best_model.pth')

    export_to_onnx('best_model.pth', device)

if __name__ == "__main__":
    main()
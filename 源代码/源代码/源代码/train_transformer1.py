import torch
import torch.nn as nn
import pandas as pd
import numpy as np
from torch.utils.data import DataLoader, TensorDataset
from sklearn.model_selection import train_test_split
from sklearn.preprocessing import StandardScaler
import os

# --- 1. 数据预处理与增强 ---
def load_and_preprocess_wisdm(file_path):
    print("正在加载数据并进行深度清洗...")
    data = []
    with open(file_path, 'r') as f:
        for line in f:
            clean_line = line.replace(';', '').strip()
            if not clean_line: continue
            parts = clean_line.split(',')
            if len(parts) == 6 and all(p.strip() != '' for p in parts[3:]):
                data.append(parts)
    
    df = pd.DataFrame(data, columns=['user', 'activity', 'timestamp', 'x', 'y', 'z'])
    for col in ['x', 'y', 'z']:
        df[col] = pd.to_numeric(df[col], errors='coerce')
    df.dropna(inplace=True)
    
    label_map = {act: i for i, act in enumerate(df['activity'].unique())}
    df['activity'] = df['activity'].map(label_map)
    
    scaler = StandardScaler()
    df[['x', 'y', 'z']] = scaler.fit_transform(df[['x', 'y', 'z']])
    return df, len(label_map)

def create_windows(df, window_size=128, step_size=64):
    X, y = [], []
    for i in range(0, len(df) - window_size, step_size):
        window = df[['x', 'y', 'z']].iloc[i:i+window_size].values
        label = df['activity'].iloc[i+window_size-1]
        X.append(window)
        y.append(label)
    return np.array(X), np.array(y)

# --- 2. MobileViT 轻量化模型 (WISDM 适配版) ---
class MobileViTBlock(nn.Module):
    def __init__(self, in_channels, out_channels, window_size=128, patch_size=8):
        super().__init__()
        self.patch_size = patch_size
        
        # 局部特征提取
        self.local_rep = nn.Sequential(
            nn.Conv1d(in_channels, in_channels, 3, padding=1),
            nn.BatchNorm1d(in_channels),
            nn.ReLU()
        )
        
        # 全局特征建模 (Transformer)
        self.global_rep = nn.TransformerEncoder(
            nn.TransformerEncoderLayer(d_model=in_channels, nhead=4, dim_feedforward=128, dropout=0.1),
            num_layers=1
        )
        
    def forward(self, x):
        # x: [B, C, L]
        res = x
        x = self.local_rep(x)
        
        # 特征展开 (针对 MobileViT 的 Patch 处理逻辑)
        B, C, L = x.shape
        x = x.permute(2, 0, 1) # [L, B, C]
        x = self.global_rep(x)
        x = x.permute(1, 2, 0) # [B, C, L]
        
        return x + res

class MobileViT_WISDM(nn.Module):
    def __init__(self, num_classes):
        super().__init__()
        self.stem = nn.Sequential(
            nn.Conv1d(3, 16, 3, padding=1),
            nn.BatchNorm1d(16),
            nn.ReLU()
        )
        
        self.block1 = MobileViTBlock(16, 16)
        self.block2 = MobileViTBlock(16, 16)
        
        self.classifier = nn.Sequential(
            nn.AdaptiveAvgPool1d(1),
            nn.Flatten(),
            nn.Linear(16, num_classes)
        )
        
    def forward(self, x):
        # 输入形状: [B, L, 3] -> 转换: [B, 3, L]
        x = x.transpose(1, 2)
        x = self.stem(x)
        x = self.block1(x)
        x = self.block2(x)
        return self.classifier(x)

# --- 3. 训练主程序 ---
if __name__ == "__main__":
    file_path = 'WISDM_ar_v1.1_raw.txt'  # 请确保数据文件存在
    if not os.path.exists(file_path):
        print(f"找不到数据文件 {file_path}")
    else:
        df, num_classes = load_and_preprocess_wisdm(file_path)
        X, y = create_windows(df)
        X_train, X_test, y_train, y_test = train_test_split(X, y, test_size=0.2, random_state=42)

        train_loader = DataLoader(TensorDataset(torch.FloatTensor(X_train), torch.LongTensor(y_train)), batch_size=64, shuffle=True)
        test_loader = DataLoader(TensorDataset(torch.FloatTensor(X_test), torch.LongTensor(y_test)), batch_size=64)

        device = torch.device('cuda' if torch.cuda.is_available() else 'cpu')
        model = MobileViT_WISDM(num_classes).to(device)
        optimizer = torch.optim.Adam(model.parameters(), lr=0.001)
        criterion = nn.CrossEntropyLoss()
        scaler = torch.amp.GradScaler('cuda') if torch.cuda.is_available() else None

        best_acc = 0
        for epoch in range(10):  # 示例训练 10 轮
            model.train()
            total_loss = 0
            for bx, by in train_loader:
                bx, by = bx.to(device), by.to(device)
                optimizer.zero_grad()
                
                if scaler:
                    with torch.amp.autocast('cuda'):
                        output = model(bx)
                        loss = criterion(output, by)
                    scaler.scale(loss).backward()
                    scaler.step(optimizer)
                    scaler.update()
                else:
                    output = model(bx)
                    loss = criterion(output, by)
                    loss.backward()
                    optimizer.step()
                    
                total_loss += loss.item()
            
            # 验证
            model.eval()
            correct, total = 0, 0
            with torch.no_grad():
                for bx, by in test_loader:
                    bx, by = bx.to(device), by.to(device)
                    _, pred = torch.max(model(bx), 1)
                    total += by.size(0)
                    correct += (pred == by).sum().item()
            
            acc = 100 * correct / total
            print(f"Epoch {epoch+1:02d} | Loss: {total_loss/len(train_loader):.4f} | Acc: {acc:.2f}%")
            
            if acc > best_acc:
                best_acc = acc
                torch.save(model.state_dict(), 'mobilevit_best.pth')

        # --- 4. 核心：导出兼容 TFLite 的优化版 ONNX ---
        print(f"\n训练完成！最高准确率: {best_acc:.2f}%。正在导出兼容性优化的 ONNX...")
        if os.path.exists('mobilevit_best.pth'):
            model.load_state_dict(torch.load('mobilevit_best.pth'))
        
        model.to('cpu').eval()
        
        # 关键修改：固定输入形状 [1, 128, 3]
        dummy_input = torch.randn(1, 128, 3)
        onnx_file_path = "mobilevit_wisdm_v11.onnx"

        torch.onnx.export(
            model, 
            dummy_input, 
            onnx_file_path,
            export_params=True,
            opset_version=11,          # 【关键】降低版本以分解复杂算子
            do_constant_folding=True,  # 【关键】折叠常量减少推理计算量
            input_names=['input'],
            output_names=['output'],
            # 注释掉 dynamic_axes 以确保转换器能精确处理 Transpose/Reshape
            # dynamic_axes={'input': {0: 'batch_size'}, 'output': {0: 'batch_size'}} 
        )

        print(f"✅ 兼容版 ONNX 导出成功: {onnx_file_path}")
        print("下一步：请将此文件上传至 Colab，直接运行 onnx2tf 即可绕过 Sequence 报错。")
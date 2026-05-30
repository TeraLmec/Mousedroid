#pragma once

#include <string>
#include <vector>

class QrCode
{
public:
    static QrCode EncodeText(const std::string& text);

    int Size() const;
    bool GetModule(int x, int y) const;

private:
    static constexpr int VERSION = 5;
    static constexpr int SIZE = 37;
    static constexpr int DATA_CODEWORDS = 108;
    static constexpr int ECC_CODEWORDS = 26;

    std::vector<std::vector<bool>> modules;
    std::vector<std::vector<bool>> functionModules;

    QrCode();

    void DrawFunctionPatterns();
    void DrawFinderPattern(int x, int y);
    void DrawAlignmentPattern(int x, int y);
    void DrawFormatBits();
    void DrawCodewords(const std::vector<unsigned char>& codewords);
    void SetFunctionModule(int x, int y, bool isBlack);
    void SetModule(int x, int y, bool isBlack);

    static std::vector<unsigned char> MakeDataCodewords(const std::string& text);
    static std::vector<unsigned char> AddEcc(const std::vector<unsigned char>& data);
    static std::vector<unsigned char> ReedSolomonGenerator(int degree);
    static std::vector<unsigned char> ReedSolomonRemainder(
        const std::vector<unsigned char>& data,
        const std::vector<unsigned char>& generator
    );
    static unsigned char GaloisMultiply(unsigned char x, unsigned char y);
    static int GetFormatBits();
};
